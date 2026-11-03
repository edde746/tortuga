package com.edde746.tortuga.app

import com.edde746.tortuga.addons.AddonEvents
import com.edde746.tortuga.addons.AddonRegistry
import com.edde746.tortuga.addons.AddonStore
import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.StoredAddon
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.addon.AddonRepository
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.ManifestChecks
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.presentation.board.addonHomeKeyPrefix
import com.edde746.tortuga.presentation.board.homeRowKeys
import com.edde746.tortuga.trackers.START_DELAY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The profile's installed addons. Its list follows the database: it loads now and again whenever the stored
 * list changes (an install on this device, or sync). A read that fails keeps the last list; the next change reads
 * it again. Following that fails (the database reads again what finds it busy) is logged and ends, the list kept
 * as last read. Once the profile is deleted (here or by sync), it stops following and is forgotten.
 */
fun AppGraph.addonRegistry(profileId: String): AddonRegistry = registries.getOrPut(profileId) {
    val registry = AddonRegistry(ProfileAddonStore(data.addons, profileId), transport)
    scope.launch {
        val following = launch {
            try {
                data.addons.observeAddons(profileId).collect {
                    try {
                        registry.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("addons", e) { "reading the installed addons failed (${e::class.simpleName}); the next change reads them again" }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("addons", e) { "following the installed addons failed (${e::class.simpleName}); the list stays as last read" }
            }
        }
        data.profiles.observeProfile(profileId).dropWhile { it == null }.first { it == null }
        following.cancel()
        if (registries[profileId] === registry) registries.remove(profileId)
    }
    registry
}

/**
 * The profile's watch-activity events to its addons ([AddonEvents]): the enabled addons the user agreed may
 * receive them, as the registry has them when each event goes.
 */
internal fun AppGraph.addonEvents(profileId: String): AddonEvents = addonEventSenders.getOrPut(profileId) {
    val registry = addonRegistry(profileId)
    AddonEvents({ registry.watchActivityAddons() }, transport, scope)
}

/**
 * Fetches [profileId]'s addons' manifests again ([refreshAddonManifests]) each time the app has been in the foreground
 * for [START_DELAY] (the first screen and Home's catalog requests go first), each addon's at most once a day
 * ([MANIFEST_MAX_AGE]). Coming to the foreground again before [START_DELAY] has passed starts the wait over. Runs in
 * this scope: the profile's session.
 */
internal fun CoroutineScope.refreshManifestsInForeground(graph: AppGraph, profileId: String) {
    launch {
        graph.foreground.filter { it }.collectLatest {
            delay(START_DELAY)
            // Outside collectLatest, so the next foreground doesn't cancel a refresh halfway; one meanwhile finds the
            // addons it asked checked.
            this@refreshManifestsInForeground.launch { graph.refreshAddonManifests(profileId, MANIFEST_MAX_AGE) }
        }
    }
}

/**
 * Fetches [profileId]'s enabled addons' manifests again ([AddonRegistry.refreshManifests]) unless the device is offline
 * (a metered network is fine: manifests are small). With [maxAge], only those this device last fetched again that long
 * ago or more, or never ([ManifestChecks]); without, all of them (the Addons screen opening). Home's layout then forgets
 * the rows of the catalogs a new manifest dropped ([ProfileSetting.HomeRows]); a row keeps its place and visibility
 * across an update, since its key (the addon's transport URL with the catalog's type, id and extra) doesn't change. A
 * failure to read or write the database is logged; the next refresh tries again.
 */
internal suspend fun AppGraph.refreshAddonManifests(profileId: String, maxAge: Duration?) {
    if (!network.value.online) {
        Log.d(TAG) { "manifests not fetched again: offline" }
        return
    }
    try {
        val registry = addonRegistry(profileId)
        registry.current()
        val installed = registry.installed.value
        val checks = ManifestChecks(profileId)
        val checked = data.deviceSettings.value(checks)
        val now = clock.now().toEpochMilliseconds()
        // A check stamped later than now (the clock was set back) counts as old.
        val due = installed.filter { it.enabled }.map { it.descriptor }.filter { addon ->
            val at = checked[addon.transportUrl]
            maxAge == null || at == null || now - at !in 0 until maxAge.inWholeMilliseconds
        }
        if (due.isEmpty()) return
        val urls = installed.mapTo(HashSet()) { it.descriptor.transportUrl }
        data.deviceSettings.update(checks) { stamps -> stamps.filterKeys { it in urls } + due.associate { it.transportUrl to now } }
        val changed = registry.refreshManifests(due)
        if (changed.isEmpty()) return
        val rows = homeRowKeys(AggrRequest.board().plan(changed)).toSet()
        data.settings.update(profileId, ProfileSetting.HomeRows) { layout ->
            layout.pruned(rows) { key -> changed.none { key.startsWith(addonHomeKeyPrefix(it.transportUrl)) } }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, e) { "fetching the manifests again failed (${e::class.simpleName}); the next refresh tries again" }
    }
}

/** How long a fetched manifest counts as current: a return to the foreground asks each addon at most once a day. */
internal val MANIFEST_MAX_AGE: Duration = 1.days

private const val TAG = "addons"

/**
 * Creates a profile on this device, a kids profile whose Home starts with [homeCatalogs] when [kids], and gives it
 * the default addons (only here: see [AddonRegistry.seedDefaults]).
 */
@Throws(IllegalArgumentException::class, kotlin.coroutines.cancellation.CancellationException::class)
suspend fun AppGraph.createProfile(name: String, avatar: String?, kids: Boolean = false, homeCatalogs: List<HomeCatalog> = emptyList()): Profile {
    val profile = data.profiles.create(name, avatar, kids, homeCatalogs)
    addonRegistry(profile.id).seedDefaults()
    return profile
}

/** The [AddonStore] of one profile over the `addon` table: the registry's list, in order, as one synced record. */
internal class ProfileAddonStore(private val addons: AddonRepository, private val profileId: String) : AddonStore {
    override suspend fun load(): List<StoredAddon> =
        addons.addons(profileId).map { StoredAddon(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) }

    override suspend fun save(addons: List<StoredAddon>) =
        this.addons.replaceAll(profileId, addons.map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) })
}
