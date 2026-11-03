package com.edde746.tortuga.addons

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.TransportUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One installed addon as persisted: its transport URL (a secret) and the manifest body, in list order, whether it is
 * [enabled] and whether the user agreed that it receives their [watchActivity].
 */
data class StoredAddon(
    val transportUrl: String,
    val manifestJson: String,
    val enabled: Boolean = true,
    val watchActivity: Boolean = false,
)

/**
 * An installed addon as the Addons screen lists it ([AddonRegistry.installed]): its [descriptor]; [enabled] off keeps
 * it in its place but asks it for nothing; [watchActivity]: the user agreed that it receives their watch activity
 * ([AddonEvents]), which only an addon whose manifest declares it ([Manifest.receivesWatchActivity]) holds.
 */
data class ProfileAddon(val descriptor: Descriptor, val enabled: Boolean, val watchActivity: Boolean)

/**
 * Persistence of one profile's installed addons, in order. The app implements it over DataLayer's `addon` table
 * (descriptor JSON and position).
 */
interface AddonStore {
    /** The installed addons in order. */
    suspend fun load(): List<StoredAddon>

    /** Replaces the stored list; list index is the position. */
    suspend fun save(addons: List<StoredAddon>)
}

/**
 * A profile's installed addons, in the user's order, following stremio-core's install, uninstall and
 * flag rules, plus an enable switch and the agreement to send watch activity. Every operation reads the [store] afresh,
 * so a list replaced by sync is never overwritten with a stale copy. A new profile gets [DefaultAddons] through
 * [seedDefaults]. Installed addons' manifests are fetched again ([refreshManifests]) and replaced when they changed.
 * Stored entries whose manifest no longer decodes are kept in the store untouched, but left out of
 * [installed] and [addons]. A stored manifest is decoded once (on the transport's parse dispatcher): an entry read
 * again with the same transport URL and body keeps its descriptor. Failures are [AddonException]s.
 */
class AddonRegistry(
    private val store: AddonStore,
    private val transport: AddonTransport,
    private val manifestTimeout: Duration = 30.seconds,
) {
    private class Entry(val stored: StoredAddon, val descriptor: Descriptor?)

    private val mutex = Mutex()
    private val state = MutableStateFlow<List<Descriptor>>(emptyList())
    private val installedState = MutableStateFlow<List<ProfileAddon>>(emptyList())

    /** The descriptors of the entries last read or written, by transport URL and manifest body; null for one that doesn't decode. */
    private var decoded: Map<Pair<String, String>, Descriptor?> = emptyMap()
    private var loaded = false

    /**
     * The enabled addons in order; empty until [load]. Every request to an addon plans with these (or [load] and
     * [current], which give the same), so a disabled addon is asked for nothing.
     */
    val addons: StateFlow<List<Descriptor>> = state.asStateFlow()

    /** Every installed addon in order, enabled or not, with the user's choices; empty until [load]. */
    val installed: StateFlow<List<ProfileAddon>> = installedState.asStateFlow()

    /** Reads the stored addons (at start, and again after sync replaced them), publishes them and returns [addons]. */
    suspend fun load(): List<Descriptor> = mutex.withLock {
        read()
        state.value
    }

    /**
     * The enabled addons as last read or written ([addons]), reading them first if nothing has been yet. The app
     * graph reads the list again whenever the stored one changes, so a screen opening needn't. A first read that fails
     * fails the caller (the database reads again what finds it busy).
     */
    suspend fun current(): List<Descriptor> = if (loaded) state.value else load()

    /** The enabled addons the user agreed may receive their watch activity, as [current] reads them. */
    suspend fun watchActivityAddons(): List<Descriptor> {
        if (!loaded) load()
        return installedState.value.filter { it.enabled && it.watchActivity }.map { it.descriptor }
    }

    /**
     * Stores [DefaultAddons] for a profile created on this device, unless it already has addons. Call it once, when
     * the profile is created, never on an empty [load]: on a newly linked device the list may just not be synced
     * yet, and seeding it would replace the user's real list everywhere.
     */
    suspend fun seedDefaults(): List<Descriptor> = mutex.withLock {
        val current = read()
        if (current.isEmpty()) {
            commit(DefaultAddons.descriptors.map { Entry(StoredAddon(it.transportUrl, DefaultAddons.manifestJson.getValue(it.transportUrl)), it) })
        }
        state.value
    }

    /**
     * Installs the addon at [link] (an install link, checked where it came in: [TransportUrl.parse]). The
     * manifest is fetched and validated. A new addon goes last, enabled; an installed transport URL gets the new
     * manifest in place and keeps its switch. [watchActivity]: the user agreed, at this install, that it receives their
     * watch activity. It is kept only for a manifest that declares it ([Manifest.receivesWatchActivity]); an update
     * keeps an agreement given before, and an addon whose new manifest declares it without one gets no events until
     * the user agrees ([setWatchActivity]).
     *
     * @throws AddonException a fetch error, [AddonError.InvalidManifest], [AddonError.ConfigurationRequired] or
     *   [AddonError.AlreadyInstalled].
     */
    suspend fun install(link: TransportUrl, watchActivity: Boolean = false): Descriptor {
        val transportUrl = link.value
        val fetched = withTimeoutOrNull(manifestTimeout) { transport.manifest(link) }
            ?: throw AddonException(AddonError.Timeout)
        if (fetched.manifest.behaviorHints.configurationRequired) {
            throw AddonException(AddonError.ConfigurationRequired(TransportUrl.configureUrl(transportUrl) ?: transportUrl))
        }
        val descriptor = Descriptor(fetched.manifest, link, DefaultAddons.flags(transportUrl))
        return mutex.withLock {
            val current = read()
            val index = current.indexOfFirst { it.stored.transportUrl == transportUrl }
            val existing = current.getOrNull(index)
            val allowed = fetched.manifest.receivesWatchActivity && (watchActivity || existing?.stored?.watchActivity == true)
            if (existing != null && existing.descriptor == descriptor && existing.stored.watchActivity == allowed) {
                throw AddonException(AddonError.AlreadyInstalled)
            }
            val entry = Entry(StoredAddon(transportUrl, fetched.json, existing?.stored?.enabled ?: true, allowed), descriptor)
            commit(if (existing != null) current.toMutableList().also { it[index] = entry } else current + entry)
            Log.i(TAG) { "installed ${descriptor.manifest.name} ${descriptor.manifest.version} from $transportUrl" }
            descriptor
        }
    }

    /** @throws AddonException [AddonError.NotInstalled] or [AddonError.Protected]. */
    suspend fun remove(transportUrl: String): Unit = mutex.withLock {
        val current = read()
        val index = current.indexOfFirst { it.stored.transportUrl == transportUrl }
        if (index < 0) throw AddonException(AddonError.NotInstalled)
        if (DefaultAddons.flags(transportUrl).protected) throw AddonException(AddonError.Protected)
        commit(current.filterIndexed { i, _ -> i != index })
    }

    /**
     * Turns the addon on or off. Off, it keeps its place in the list and is asked for nothing: it leaves [addons].
     * The addon that can't be removed can't be turned off either.
     *
     * @throws AddonException [AddonError.NotInstalled] or, turning the built-in addon off, [AddonError.Protected].
     */
    suspend fun setEnabled(transportUrl: String, enabled: Boolean): Unit = mutex.withLock {
        if (!enabled && DefaultAddons.flags(transportUrl).protected) throw AddonException(AddonError.Protected)
        update(transportUrl) { it.copy(enabled = enabled) }
    }

    /**
     * Records whether the user agrees that the addon receives their watch activity ([AddonEvents]). An agreement
     * holds only while its manifest declares it ([Manifest.receivesWatchActivity]).
     *
     * @throws AddonException [AddonError.NotInstalled].
     */
    suspend fun setWatchActivity(transportUrl: String, allowed: Boolean): Unit = mutex.withLock {
        update(transportUrl) { it.copy(watchActivity = allowed) }
    }

    /** Moves the addon to [toIndex] (clamped) among the installed addons. @throws AddonException [AddonError.NotInstalled]. */
    suspend fun move(transportUrl: String, toIndex: Int): Unit = mutex.withLock {
        val current = read().toMutableList()
        val from = current.indexOfFirst { it.stored.transportUrl == transportUrl }
        if (from < 0) throw AddonException(AddonError.NotInstalled)
        val entry = current.removeAt(from)
        current.add(toIndex.coerceIn(0, current.size), entry)
        commit(current)
    }

    /**
     * Fetches the manifests of [addons] (installed ones, as [installed] lists them) again, as Stremio does, so an addon
     * reconfigured on its site or updated by its author (catalogs, resources, types, id prefixes, hints) is used as it
     * is now without being installed again. A manifest whose content changed replaces the stored one in place: the
     * addon keeps its position, its switch and the user's agreement to send it watch activity (while the new manifest
     * declares it, as [install] keeps it). One whose content is the same stores nothing, so no device rewrites the
     * synced list for a body that only looks different. A fetch that fails, a manifest that doesn't decode and one with
     * another `id` (another addon now at that URL) keep the stored manifest and are logged. Returns the addons stored
     * with a new manifest.
     */
    suspend fun refreshManifests(addons: List<Descriptor>): List<Descriptor> {
        val fetched = coroutineScope {
            addons.map { addon -> async { addon.transportUrl to fetchAgain(addon) } }.awaitAll()
        }.mapNotNull { (url, manifest) -> manifest?.let { url to it } }.toMap()
        if (fetched.isEmpty()) return emptyList()
        return mutex.withLock {
            val current = read()
            val changed = mutableListOf<Descriptor>()
            val next = current.map { entry ->
                val now = fetched[entry.stored.transportUrl]
                val stored = entry.descriptor
                if (now == null || stored == null) return@map entry
                if (now.manifest.id != stored.manifest.id) {
                    Log.w(TAG) { "keeping the stored manifest of ${stored.manifest.id} at ${stored.transportUrl}: it now serves ${now.manifest.id}" }
                    return@map entry
                }
                val descriptor = stored.copy(manifest = now.manifest)
                if (descriptor == stored) return@map entry
                changed += descriptor
                Log.i(TAG) { "${stored.manifest.name} ${stored.manifest.version} at ${stored.transportUrl} has a new manifest (${now.manifest.version})" }
                val watchActivity = entry.stored.watchActivity && now.manifest.receivesWatchActivity
                Entry(entry.stored.copy(manifestJson = now.json, watchActivity = watchActivity), descriptor)
            }
            if (changed.isNotEmpty()) commit(next)
            changed
        }
    }

    /** [addon]'s manifest as its URL serves it now; null, logged, when the fetch fails or the manifest is invalid. */
    private suspend fun fetchAgain(addon: Descriptor): FetchedManifest? = try {
        withTimeoutOrNull(manifestTimeout) { transport.manifest(addon.transport) } ?: throw AddonException(AddonError.Timeout)
    } catch (e: AddonException) {
        Log.w(TAG) { "keeping the stored manifest of ${addon.manifest.id} at ${addon.transportUrl}: ${e.error}" }
        null
    }

    /** Stores [change] of the entry at [transportUrl]; an agreement without a manifest that declares it is dropped. */
    private suspend fun update(transportUrl: String, change: (StoredAddon) -> StoredAddon) {
        val current = read()
        val index = current.indexOfFirst { it.stored.transportUrl == transportUrl }
        if (index < 0) throw AddonException(AddonError.NotInstalled)
        val entry = current[index]
        val changed = change(entry.stored).let { it.copy(watchActivity = it.watchActivity && entry.descriptor?.manifest?.receivesWatchActivity == true) }
        if (changed == entry.stored) return
        commit(current.toMutableList().also { it[index] = Entry(changed, entry.descriptor) })
    }

    private suspend fun read(): List<Entry> {
        val stored = store.load()
        val known = decoded
        val entries = if (stored.all { it.key in known }) {
            stored.map { Entry(it, known[it.key]) }
        } else {
            withContext(transport.parseDispatcher) { stored.map { Entry(it, if (it.key in known) known[it.key] else decode(it)) } }
        }
        publish(entries)
        return entries
    }

    private fun decode(stored: StoredAddon): Descriptor? = try {
        val transport = TransportUrl.checked(stored.transportUrl)
        if (transport == null) {
            Log.w(TAG) { "stored addon ${stored.transportUrl} isn't at a transport URL; keeping it stored but unused" }
            null
        } else {
            Descriptor(Manifest.decode(stored.manifestJson), transport, DefaultAddons.flags(stored.transportUrl))
        }
    } catch (e: SerializationException) {
        Log.w(TAG, e) { "stored addon ${stored.transportUrl} has an invalid manifest; keeping it stored but unused" }
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, e) { "stored addon ${stored.transportUrl} has an invalid manifest; keeping it stored but unused" }
        null
    }

    private suspend fun commit(next: List<Entry>) {
        store.save(next.map { it.stored })
        publish(next)
    }

    private fun publish(entries: List<Entry>) {
        decoded = entries.associate { it.stored.key to it.descriptor }
        loaded = true
        // The built-in addon is on whatever the stored switch says (an older or other client may have stored it off).
        val profileAddons = entries.mapNotNull { entry ->
            entry.descriptor?.let { ProfileAddon(it, entry.stored.enabled || it.flags.protected, entry.stored.watchActivity) }
        }
        installedState.value = profileAddons
        state.value = profileAddons.filter { it.enabled }.map { it.descriptor }
    }

    private val StoredAddon.key: Pair<String, String> get() = transportUrl to manifestJson

    companion object {
        private const val TAG = "addons"

        /** The addon's "Configure" page, `{base}/configure`, when its manifest says it has one. */
        fun configureUrl(addon: Descriptor): String? =
            TransportUrl.configureUrl(addon.transportUrl)
                .takeIf { addon.manifest.behaviorHints.configurable || addon.manifest.behaviorHints.configurationRequired }
    }
}
