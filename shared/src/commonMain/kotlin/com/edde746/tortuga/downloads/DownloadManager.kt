package com.edde746.tortuga.downloads

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.data.downloads.DownloadRow
import com.edde746.tortuga.data.downloads.DownloadStream
import com.edde746.tortuga.data.downloads.Enqueued
import com.edde746.tortuga.data.downloads.NewDownload
import com.edde746.tortuga.data.downloads.WatchState
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.playback.session.StreamChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Offline downloads on this device: a durable queue of videos, each downloaded from the original stream
 * file an addon offered, with its subtitles, meta and artwork, so it plays and shows offline. Per device, never synced;
 * each download belongs to the profiles that asked for it.
 *
 * - **Queue:** downloads run oldest first, [DeviceSettings.downloadsAtOnce][com.edde746.tortuga.data.settings.DeviceSettings.downloadsAtOnce]
 *   at a time (1 by default). A transfer that fails is retried 3 times, 30 s apart; 3 downloads failing in a row stop
 *   the queue until the user acts; a full disk stops it too.
 * - **Each attempt** resolves the stream right before it starts ([com.edde746.tortuga.playback.resolve.StreamResolver]:
 *   the resolved link's final URL, size, hash and file name), checks the free space against the size, and transfers into
 *   a `.part` file through the platform's [DownloadTransport], resuming with `Range`/`If-Range`. A 403, 404 or 410
 *   re-resolves from the original stream (the addon's streams fetched again, the same stream found as the stream
 *   history finds it); the transfer restarts from zero only when the new file isn't the same. The final size is
 *   verified and the file moved into place atomically.
 * - **Network:** nothing runs offline, nor on a metered network with Wi-Fi only on (the setting, else the platform's
 *   default); a transfer running when that changes goes back to the queue.
 * - **Extras** once the file is complete: the artwork (poster, background, logo, the episode's thumbnail) and the addon
 *   subtitles for the subtitle languages to save; the meta JSON is saved when the download is queued.
 * - **Seasons and Keep synced:** [enqueueEpisodes] picks each episode's stream automatically; [keepSynced] keeps a series
 *   at N unwatched episodes, topped up after a watch, at launch and on network changes, never deleting; a rule can be
 *   paused ([pauseKeepSynced]) and stopped ([stopKeepSynced]), which can delete what it queued; what the rules queue on
 *   their own is told once ([keepSyncedNotice]). Auto-remove watched (a device setting, off by default) deletes watched
 *   downloads, never the one playing.
 * - **Owners:** a download without owners (its profiles deleted, the account signed out) is deleted with its files.
 *
 * Built by [installDownloads]; null on [AppGraph.downloads] where a platform has no downloads (Apple TV). Call from the
 * main thread; the flows may be collected anywhere.
 */
class DownloadManager internal constructor(
    private val env: DownloadEnv,
    private val scope: CoroutineScope,
    timeSource: TimeSource,
) {
    private val repo get() = env.repo
    private val transfers = Transfers(
        transport = env.platform.transport,
        scope = scope,
        timeSource = timeSource,
        persist = { id, bytes -> scope.launch { logFailures("saving download $id's progress") { repo.setProgress(id, bytes) } } },
        validator = { id, validator -> scope.launch { logFailures("saving download $id's validator") { repo.setValidator(id, validator) } } },
    )
    private val worker = DownloadWorker(env, transfers, timeSource)
    private val extras = DownloadExtras(env)
    private val picker = EpisodePicker(env)

    private val rows = MutableStateFlow<List<DownloadRow>?>(null)
    private val mutableLocations = MutableStateFlow(DownloadLocations(emptyList(), DownloadLocation.INTERNAL))
    private val mutableStorage = MutableStateFlow(DownloadStorage(0, null))
    private val usedOnDisk = MutableStateFlow(0L)
    private val freeBytes = MutableStateFlow<Long?>(null)

    /** The downloads running now (resolving, downloading, or waiting to retry), on the main thread. */
    private val running = mutableMapOf<Long, Running>()
    private val runningIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Why the queue stopped until the user acts; null while it runs. */
    private val stopped = MutableStateFlow<QueueStop?>(null)
    private var failureStreak = 0
    private var ready = false

    /** Completes once the launch's reconcile is done; actions wait for it. */
    private val started = CompletableDeferred<Unit>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val purging = mutableSetOf<Long>()

    /**
     * Whether [locations] were read, and for which location setting: a read that failed leaves both, so the next
     * settings read them again.
     */
    private var located = false
    private var locatedFor: String? = null
    private val playing = mutableSetOf<Long>()

    /** Removals (auto-remove, a stopped rule's downloads) held back while their download played: (profile, download). */
    private val heldRemovals = mutableSetOf<Pair<String, Long>>()
    private val extrasLock = Mutex()
    private val syncLock = Mutex()

    /** What the rules queued on their own per profile, until the profile saw it ([dismissKeepSyncedNotice]). */
    private val notices = MutableStateFlow<Map<String, KeepSyncedNotice>>(emptyMap())

    /** Every download on this device, with live progress (written to the database at most every 2 s). */
    val all: StateFlow<List<Download>> = combine(rows.filterNotNull(), transfers.live, env.device, env.network, mutableLocations) { rows, live, _, network, locations ->
        val waiting = network.online && network.metered && env.wifiOnly()
        rows.map { it.toDownload(live[it.id], waiting, locations) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** [profileId]'s downloads (the ones it owns), most recently added first. */
    fun downloads(profileId: String): Flow<List<Download>> = all.map { downloads -> downloads.filter { profileId in it.owners } }

    /** The download location's space; refreshed as downloads change and when asked ([refreshStorage]). */
    val storage: StateFlow<DownloadStorage> = mutableStorage.asStateFlow()

    /** Some download is queued, resolving or downloading and the network allows it: Android's worker runs while true. */
    val active: StateFlow<Boolean> = combine(rows.filterNotNull(), runningIds, stopped, env.network, env.device) { rows, running, stop, network, _ ->
        running.isNotEmpty() || (stop == null && env.networkAllows(network) && rows.any { it.status == DownloadStatus.QUEUED && it.owners.isNotEmpty() })
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Where downloads can go now ([DownloadPlatform.locations]), and the one new downloads use. */
    val locations: StateFlow<DownloadLocations> = mutableLocations.asStateFlow()

    /** Settings offers a folder picker ([DownloadPlatform.choosesFolder]). */
    val choosesFolder: Boolean get() = env.platform.choosesFolder

    /** Whether the Wi-Fi only setting is offered here ([DownloadPlatform.wifiOnlyDefault]). */
    val offersWifiOnly: Boolean get() = env.platform.wifiOnlyDefault != null

    /** Wi-Fi only's value while [DeviceSettings.downloadWifiOnly] is unset: the platform's default, else off. */
    val wifiOnlyDefault: Boolean get() = env.platform.wifiOnlyDefault ?: false

    /**
     * Queues [videoId] of [meta] from [choice] for [profileId] (the Streams sheet's pick in download mode, or its
     * Download action). A video already downloaded or queued gets [profileId] as an owner instead (and a failed or
     * cancelled one is queued again from [choice]). Returns the download.
     */
    suspend fun enqueue(profileId: String, meta: MetaItem, videoId: String, choice: StreamChoice): Download {
        started.await()
        userActed()
        val id = insert(profileId, meta, videoId, choice, keepSynced = false).id
        wake()
        return download(id) ?: error("download $id vanished")
    }

    /**
     * "Download season…": queues [pick] of [season]'s episodes of [meta] (every season's when [season] is null), each
     * episode's stream picked automatically: the same addon and `bingeGroup` as [reference] when given (and as the last
     * download of the series otherwise), else the profile's auto-play rule with its filters; streams an addon marks not
     * ready are never picked. An episode with no pick is a failed download with
     * [DownloadFailureKind.NO_STREAM]. Episodes already downloaded or queued are skipped. Returns how many it queued.
     */
    suspend fun enqueueEpisodes(profileId: String, meta: MetaItem, season: Int?, pick: SeasonPick, reference: StreamChoice?): Int {
        started.await()
        userActed()
        val item = env.library.item(profileId, meta.id)
        val videos = picker.episodes(meta, season, pick, item, env.clock.now())
        val queued = queueEpisodes(profileId, meta, videos, reference, keepSynced = false)
        wake()
        return queued
    }

    /**
     * Keep synced for [meta] (a series) and [profileId] at [rule]'s count: a paused rule runs again. Runs the rule now.
     */
    suspend fun keepSynced(profileId: String, meta: MetaItem, rule: KeepSynced) {
        started.await()
        userActed()
        repo.setRule(profileId, meta.id, meta.type, rule.episodes, meta.name, meta.preview.poster)
        runRule(profileId, meta.id, force = true, known = meta)
    }

    /**
     * [profileId]'s Keep synced rules, by the series' name, each with the series' name and poster as this device saved
     * them: no addon is asked.
     */
    fun keepSyncedRules(profileId: String): Flow<List<KeepSyncedRule>> = combine(repo.observeRules(profileId), mutableLocations) { rules, locations ->
        rules.map { rule ->
            KeepSyncedRule(
                metaId = rule.metaId,
                type = rule.type,
                title = rule.title ?: rule.metaId,
                poster = savedUrl(rule.poster, rule.artwork, rule.artworkLocationId, locations),
                rule = KeepSynced(rule.episodes),
                paused = rule.paused,
            )
        }
    }.distinctUntilChanged()

    /**
     * Pauses [profileId]'s rule for [metaId] ([paused]): it doesn't run, and the downloads it queued carry on. Resumed,
     * it runs now.
     */
    fun pauseKeepSynced(profileId: String, metaId: String, paused: Boolean): Deferred<Unit> =
        act(if (paused) "pausing Keep synced for $metaId" else "resuming Keep synced for $metaId") {
            repo.setRulePaused(profileId, metaId, paused)
            if (paused) return@act
            userActed()
            runRule(profileId, metaId, force = true)
        }

    /**
     * Stops [profileId]'s rule for [metaId]. With [deleteDownloads], [profileId] also lets go of the downloads the rule
     * queued ([Download.keepSyncedEpisode]; a run still going finishes first), the one playing once it ends; the ones
     * asked for by hand stay.
     */
    fun stopKeepSynced(profileId: String, metaId: String, deleteDownloads: Boolean): Deferred<Unit> = act("stopping Keep synced for $metaId") {
        repo.deleteRule(profileId, metaId)
        if (!deleteDownloads) return@act
        syncLock.withLock {
            for (row in repo.downloads().filter { it.metaId == metaId && it.keepSynced && profileId in it.owners }) {
                if (row.id in playing) heldRemovals += profileId to row.id else disown(profileId, row.id)
            }
        }
    }

    /** What [profileId]'s rules queued on their own since it was last [dismissKeepSyncedNotice]d; null while nothing. */
    fun keepSyncedNotice(profileId: String): Flow<KeepSyncedNotice?> = notices.map { it[profileId] }.distinctUntilChanged()

    /** [profileId] saw its [keepSyncedNotice]. */
    fun dismissKeepSyncedNotice(profileId: String) {
        notices.update { it - profileId }
    }

    /** Pauses [id], keeping what arrived. Returns the outcome ([act]), as every command here does. */
    fun pause(id: Long): Deferred<Unit> = act("pausing download $id") { pauseNow(id) }

    /** Resumes a paused [id] (queued again, its failed attempts starting over: the user acted). */
    fun resume(id: Long): Deferred<Unit> = act("resuming download $id") {
        userActed()
        resumeNow(id)
        wake()
    }

    /** Queues a failed or cancelled [id] again (an episode without a stream needs [enqueue] with a pick instead). */
    fun retry(id: Long): Deferred<Unit> = act("retrying download $id") {
        userActed()
        val row = repo.download(id) ?: return@act
        if (row.status == DownloadStatus.FAILED || row.status == DownloadStatus.CANCELLED) repo.requeue(id)
        wake()
    }

    /** Cancels [id]: its transfer stops and its partial file goes. */
    fun cancel(id: Long): Deferred<Unit> = act("cancelling download $id") {
        runOf(id)?.let {
            it.stop(StopReason.CANCEL)
            return@act
        }
        val row = repo.download(id) ?: return@act
        if (row.status == DownloadStatus.COMPLETED || row.status == DownloadStatus.CANCELLED) return@act
        discardPart(row)
        repo.setStatus(id, DownloadStatus.CANCELLED)
    }

    /** Removes [profileId] from [id]'s owners; with none left, the download and its files go. */
    fun delete(profileId: String, id: Long): Deferred<Unit> = act("deleting download $id") { disown(profileId, id) }

    /** [delete] for every download of [metaId] [profileId] owns (a series, a season's worth with [season]). */
    fun deleteMeta(profileId: String, metaId: String, season: Int? = null): Deferred<Unit> = act("deleting the downloads of $metaId") {
        repo.downloads()
            .filter { it.metaId == metaId && profileId in it.owners && (season == null || it.season == season) }
            .forEach { disown(profileId, it.id) }
    }

    /** Pauses every running or queued download of [profileId]. */
    fun pauseAll(profileId: String): Deferred<Unit> = act("pausing every download") {
        repo.downloads().filter { profileId in it.owners }.forEach { pauseNow(it.id) }
    }

    /** Resumes every paused (and queues every failed) download of [profileId]; clears a stopped queue. */
    fun resumeAll(profileId: String): Deferred<Unit> = act("resuming every download") {
        userActed()
        for (row in repo.downloads().filter { profileId in it.owners }) {
            when {
                row.status == DownloadStatus.FAILED -> repo.requeue(row.id)
                row.status == DownloadStatus.PAUSED || row.id in running -> resumeNow(row.id)
            }
        }
        wake()
    }

    /**
     * Deletes every download on this device, for every profile (Settings → Downloads → Delete all): every one loses its
     * owners and Keep synced rules, and each is purged; one whose location isn't there stays, ownerless, until it is.
     */
    fun deleteAll(): Deferred<Unit> = act("deleting every download") {
        userActed()
        repo.disownAll()
        repo.downloads().forEach { purge(it.id) }
        running.values.map { it.job }.forEach { it?.join() }
        refreshStorageNow()
    }

    /** Reads the free space again. */
    fun refreshStorage() {
        scope.launch {
            logFailures("reading the download locations") { refreshLocations() }
            logFailures("measuring the downloads' storage") { refreshStorageNow() }
        }
    }

    /** Where new downloads go: one of [locations]' ids, or a folder ([DownloadLocation.folder]) the user picked. */
    fun setLocation(id: String) {
        scope.launch { env.deviceSettings.set(DeviceSetting.DownloadLocation, id) }
    }

    /**
     * [videoId] of [metaId] as [profileId] can play it from the device: completed, owned and its file present; else null.
     *
     * [failIfGone] (the Downloads screen's plays): a completed download whose file is gone fails with why, so its row says
     * so and offers Retry (which downloads it again) and Delete: [DownloadFailureKind.LOCATION_UNAVAILABLE] while its
     * location, or the location's folder, can't be found (a card or drive removed), [DownloadFailureKind.FILE_MISSING]
     * when the folder is there without the file (deleted outside the app; what arrived counts as nothing again).
     */
    suspend fun localVideo(profileId: String, metaId: String, videoId: String, failIfGone: Boolean = false): LocalVideo? {
        val row = repo.byVideo(metaId, videoId)?.takeIf { it.status == DownloadStatus.COMPLETED } ?: return null
        if (profileId !in repo.owners(row.id)) return null
        val path = row.relativePath ?: return null
        val directory = env.locationPath(row.locationId)
        val file = directory?.let { child(it, path) }
        val size = file?.let { withContext(env.io) { env.files.size(it) } }
        if (directory == null || file == null || size == null) {
            if (failIfGone) {
                val folder = directory != null && withContext(env.io) { env.files.exists(Path(directory)) }
                val kind = if (folder) DownloadFailureKind.FILE_MISSING else DownloadFailureKind.LOCATION_UNAVAILABLE
                Log.w(TAG) { "download ${row.id}: its file is gone ($kind)" }
                repo.setStatus(row.id, DownloadStatus.FAILED, DownloadFailure(kind))
                if (kind == DownloadFailureKind.FILE_MISSING) repo.setProgress(row.id, 0)
            }
            return null
        }
        val saved = repo.subtitles(row.id)
        val subtitles = withContext(env.io) {
            saved.mapNotNull { subtitle ->
                child(directory, subtitle.relativePath).takeIf(env.files::exists)?.let { LocalSubtitle(it.toString(), subtitle.title, subtitle.language) }
            }
        }
        val filename = row.filename ?: file.name
        // A stream that can't be read (logged as the row was read) doesn't keep the file from playing: it plays as
        // itself (the player names the file), a stream that can't be played again, so the stream history keeps what it
        // had.
        val stream = (row.stream as? DownloadStream.Picked)?.choice ?: StreamChoice(
            Stream(
                StreamSource.Url(file.toString()),
                name = filename,
                behaviorHints = StreamBehaviorHints(filename = filename, videoHash = row.videoHash, videoSize = size),
            ),
            addonUrl = "",
            addonName = "",
        )
        return LocalVideo(row.id, file.toString(), size, row.videoHash, filename, subtitles, stream)
    }

    /**
     * The meta saved with [id]'s downloads (artwork as `file://` URLs where saved), for Detail offline; null when none,
     * or when it can't be read (logged as it was read): the callers then have the addons' meta or the download's own.
     */
    suspend fun savedMeta(type: String, id: String): MetaItem? {
        val saved = repo.savedMeta(id) ?: return null
        val meta = saved.meta ?: return null
        fun local(url: String?): String? = savedUrl(url, saved.artwork, saved.locationId, mutableLocations.value)
        return meta.copy(
            preview = meta.preview.copy(
                poster = local(meta.preview.poster),
                background = local(meta.preview.background),
                logo = local(meta.preview.logo),
            ),
            videos = meta.videos.map { it.copy(thumbnail = local(it.thumbnail)) },
        )
    }

    /**
     * Runs a user's action ([what]) once the launch's reconcile is done, in the manager's scope: it carries on when the
     * screen that asked goes. Its outcome is returned: one that throws is logged and fails the [Deferred], so the screen
     * that awaits it can say so (the list keeps showing the download as it was, and the user can act again).
     */
    private fun act(what: String, action: suspend () -> Unit): Deferred<Unit> {
        val outcome = CompletableDeferred<Unit>()
        scope.launch {
            try {
                started.await()
                action()
                outcome.complete(Unit)
            } catch (e: CancellationException) {
                outcome.cancel(e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "$what failed (${e::class.simpleName})" }
                outcome.completeExceptionally(e)
            }
        }
        return outcome
    }

    /** [downloadId] is playing (auto-remove and a stopped rule leave it alone) until [playbackEnded]. */
    internal fun playbackStarted(downloadId: Long) {
        playing += downloadId
    }

    internal fun playbackEnded(downloadId: Long) {
        playing -= downloadId
        val held = heldRemovals.filter { it.second == downloadId }
        heldRemovals.removeAll(held.toSet())
        if (held.isNotEmpty()) scope.launch { held.forEach { (profileId, id) -> disown(profileId, id) } }
    }

    // ---- Startup ------------------------------------------------------------------------------------------------------

    /**
     * Picks up where the last process left the queue: downloads without owners go, transfers still running are
     * reattached and the rest queued again, part files nothing refers to go; then the queue runs. A step that throws is
     * logged and the rest run, so the queue starts and actions never wait for a launch that can't end: the followers
     * read the settings and the locations again and purge downloads without owners; the next launch reconciles the rest.
     */
    internal fun start() {
        scope.launch {
            logFailures("reading the device settings at launch") { env.device.value = env.deviceSettings.settings() }
            logFailures("reading the download locations at launch") { refreshLocations() }
            logFailures("deleting downloads without owners at launch") {
                repo.downloads().filter { it.owners.isEmpty() }.forEach { deleteFiles(it.id) }
            }
            logFailures("reattaching the transfers still running") { reattach() }
            logFailures("queueing interrupted downloads again") { repo.requeueInterrupted() }
            logFailures("deleting part files nothing refers to") { sweepOrphanParts() }
            ready = true
            started.complete(Unit)
            follow()
            wake()
            launch { saveMissingExtras() }
            launch { runRules(force = false) }
        }
    }

    /**
     * Asks the transport which of the transfers the database had running still run (asked even with none, so a
     * transport can let go of transfers no download wants any more), and waits for those.
     */
    private suspend fun reattach() {
        val rows = repo.transferring()
        val results = rows.associate { it.id to transfers.expect(it.id.toString()) }
        val still = withTimeoutOrNull(REATTACH_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                env.platform.transport.reattach(rows.map { it.id.toString() }, transfers) { running ->
                    if (continuation.isActive) continuation.resume(running.toSet())
                }
            }
        }.orEmpty()
        for (row in rows) {
            if (row.id.toString() in still && row.relativePath != null) {
                start(row.id, results.getValue(row.id))
            } else {
                transfers.forget(row.id.toString())
                repo.setTransferring(row.id, false)
                if (row.status == DownloadStatus.RESOLVING || row.status == DownloadStatus.DOWNLOADING) repo.setStatus(row.id, DownloadStatus.QUEUED)
            }
        }
    }

    /**
     * Collects what the queue follows: the downloads, the device's settings, the network, the watch states. A change
     * whose work throws (a database too busy to answer, a location that can't be read) is logged and following goes
     * on; what failed is tried again with the next change.
     */
    private fun follow() {
        scope.launch {
            // How many downloads were complete when the storage was last measured.
            var measured: Int? = null
            repo.observeDownloads().collect { list ->
                rows.value = list
                logFailures("purging downloads without owners") {
                    list.filter { it.owners.isEmpty() && it.id !in purging }.forEach { purge(it.id) }
                }
                logFailures("measuring the downloads' storage") {
                    val completed = list.count { it.status == DownloadStatus.COMPLETED }
                    if (completed == measured) {
                        refreshFree()
                    } else {
                        refreshStorageNow()
                        measured = completed
                    }
                }
            }
        }
        scope.launch {
            env.deviceSettings.observe().collect { settings ->
                env.device.value = settings
                if (!located || settings.downloadLocation != locatedFor) logFailures("reading the download locations") { refreshLocations() }
                if (!env.networkAllows()) stopRunning(StopReason.REQUEUE)
                wake()
            }
        }
        scope.launch {
            var previous: NetworkState? = null
            env.network.collect { network ->
                val before = previous
                previous = network
                if (!env.networkAllows(network)) {
                    stopRunning(StopReason.REQUEUE)
                    return@collect
                }
                wake()
                if (before != null && (!before.online || before.metered != network.metered)) {
                    launch { saveMissingExtras() }
                    launch { runRules(force = false) }
                }
            }
        }
        scope.launch {
            var previous: Map<Pair<String, String>, WatchState>? = null
            repo.observeWatchStates().collect { states ->
                val before = previous
                previous = states
                if (before == null) return@collect
                for ((key, state) in states) {
                    if (watchedMore(before[key], state)) launch { afterWatch(key.first, key.second) }
                }
            }
        }
        scope.launch {
            combine(active, env.device) { active, _ -> active to env.wifiOnly() }.distinctUntilChanged().collect { (active, wifiOnly) ->
                logFailures("telling the platform whether the queue is active") { env.platform.onQueueActive(active, wifiOnly) }
            }
        }
        scope.launch {
            for (signal in wake) logFailures("scheduling the queue") { schedule() }
        }
    }

    /**
     * Runs [block], work a follower ([follow]), the launch or a user's action does: an exception is logged as [what]
     * failing and the caller goes on; its next trigger (a change, the network, the next launch, the user) tries again.
     * Cancellation still ends the caller.
     */
    private inline fun logFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, e) { "$what failed (${e::class.simpleName})" }
        }
    }

    // ---- The queue ----------------------------------------------------------------------------------------------------

    private fun wake() {
        wake.trySend(Unit)
    }

    /** Starts queued downloads in the queue's order while there are free slots and the network allows. */
    private suspend fun schedule() {
        if (!ready || stopped.value != null || !env.networkAllows()) return
        val slots = (env.device.value?.downloadsAtOnce ?: 1) - running.size
        if (slots <= 0) return
        repo.queued().asSequence()
            .filter { it.id !in running && it.id !in purging }
            .take(slots)
            .forEach { start(it.id, attached = null) }
    }

    private fun start(id: Long, attached: CompletableDeferred<TransferResult>?) {
        val entry = Running()
        running[id] = entry
        runningIds.value = running.keys.toSet()
        entry.job = scope.launch {
            val outcome = try {
                attempt(id, attached, entry)
            } catch (e: CancellationException) {
                if (entry.reason == null) throw e
                null
            }
            withContext(NonCancellable) { finish(id, entry, outcome) }
        }
    }

    /**
     * [DownloadWorker.run], run again [DownloadWorker.RETRY_DELAY] later when it throws (in practice the database: a
     * SQLITE_BUSY while the disk is saturated), [DownloadWorker.MAX_RETRIES] times; then the download fails as
     * [DownloadFailureKind.STORAGE]. The tries are counted in memory, as the database may be what fails.
     */
    private suspend fun attempt(id: Long, attached: CompletableDeferred<TransferResult>?, entry: Running): Outcome {
        var reattached = attached
        var failures = 0
        while (true) {
            try {
                return worker.run(id, reattached) { entry.reason != null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "download $id: the attempt threw (${e::class.simpleName})" }
                if (++failures > DownloadWorker.MAX_RETRIES) return Outcome.Failed(DownloadFailureKind.STORAGE)
            }
            reattached = null
            delay(DownloadWorker.RETRY_DELAY)
        }
    }

    /**
     * A run ended: [outcome], or the stop the user (or the network) asked for. The run stays in [running], marked
     * [Running.ending], until its state is written ([writeEnd]); an action meanwhile waits for that ([runOf]).
     */
    private suspend fun finish(id: Long, entry: Running, outcome: Outcome?) {
        entry.ending = true
        val reason = entry.reason
        try {
            val reported = transfers.live.value[id]?.bytes
            transfers.clear(id)
            // A pause or a network hold keeps the resolve for the next attempt; any other end forgets it.
            if (reason != StopReason.PAUSE && reason != StopReason.REQUEUE && outcome != Outcome.Requeue) worker.forget(id)
            writeEnd(id, reason, outcome, reported)
        } finally {
            running.remove(id)
            runningIds.value = running.keys.toSet()
            entry.ended.complete(Unit)
        }
        if (id in purging && reason != StopReason.DELETE) logFailures("deleting download $id") { deleteFiles(id) }
        wake()
    }

    /**
     * Writes how [id]'s run ended, again [DownloadWorker.RETRY_DELAY] later when that throws (the database),
     * [DownloadWorker.MAX_RETRIES] times, so a pause or a cancel isn't lost to a busy disk. Each branch writes before
     * it counts, so a retry counts once. Past the retries the row stays as the run left it, and the next launch queues
     * it again.
     */
    private suspend fun writeEnd(id: Long, reason: StopReason?, outcome: Outcome?, reported: Long?) {
        var failures = 0
        while (true) {
            try {
                when {
                    reason != null -> applyStop(id, reason, reported)
                    outcome is Outcome.Completed -> {
                        failureStreak = 0
                        scope.launch { logFailures("saving download $id's extras") { repo.download(id)?.let { saveExtras(listOf(it)) } } }
                    }
                    outcome is Outcome.Failed -> {
                        repo.setTransferring(id, false)
                        repo.setStatus(id, DownloadStatus.FAILED, outcome.failure)
                        Log.i(TAG) { "download $id failed: ${outcome.failure}" }
                        if (++failureStreak >= MAX_FAILURE_STREAK) {
                            Log.w(TAG) { "$failureStreak downloads failed in a row; the queue waits for the user" }
                            stopped.value = QueueStop.FAILURES
                        }
                    }
                    outcome == Outcome.DiskFull -> diskFull(id)
                    outcome == Outcome.Requeue -> {
                        repo.setTransferring(id, false)
                        keepProgress(id, reported)
                        repo.setStatus(id, DownloadStatus.QUEUED)
                    }
                    else -> {}
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "download $id: writing how its run ended failed (${e::class.simpleName})" }
                if (++failures > DownloadWorker.MAX_RETRIES) return
            }
            delay(DownloadWorker.RETRY_DELAY)
        }
    }

    /** [reported]: the bytes the transfer last reported (a platform may keep them as resume data, not in the part). */
    private suspend fun applyStop(id: Long, reason: StopReason, reported: Long?) {
        repo.setTransferring(id, false)
        when (reason) {
            StopReason.PAUSE -> {
                keepProgress(id, reported)
                repo.setStatus(id, DownloadStatus.PAUSED)
            }
            StopReason.REQUEUE -> {
                keepProgress(id, reported)
                repo.setStatus(id, DownloadStatus.QUEUED)
            }
            StopReason.CANCEL -> {
                repo.download(id)?.let { discardPart(it) }
                repo.setStatus(id, DownloadStatus.CANCELLED)
            }
            StopReason.DISK_FULL -> {
                keepProgress(id, reported)
                repo.setStatus(id, DownloadStatus.FAILED, DownloadFailure(DownloadFailureKind.DISK_FULL))
            }
            StopReason.DELETE -> deleteFiles(id)
        }
    }

    /** The volume is full: every queued or running download fails with DISK_FULL and the queue stops. */
    private suspend fun diskFull(id: Long) {
        Log.w(TAG) { "the download location is full; the queue stops" }
        stopped.value = QueueStop.DISK_FULL
        repo.setTransferring(id, false)
        keepProgress(id, null)
        val failure = DownloadFailure(DownloadFailureKind.DISK_FULL)
        repo.setStatus(id, DownloadStatus.FAILED, failure)
        running.filterKeys { it != id }.values.forEach { it.stop(StopReason.DISK_FULL) }
        val waiting = repo.withStatus(listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING))
            .filter { it.id !in running }
            .map { it.id }
        repo.setStatuses(waiting, DownloadStatus.FAILED, failure)
    }

    /**
     * The download's progress once its transfer stopped: the part file's size, or the bytes the transfer last
     * [reported] when more (Apple keeps them as resume data until the transfer resumes).
     */
    private suspend fun keepProgress(id: Long, reported: Long?) {
        val row = repo.download(id) ?: return
        val directory = env.locationPath(row.locationId) ?: return
        val path = row.relativePath ?: return
        val bytes = withContext(env.io) { env.files.size(Path("${child(directory, path)}${DownloadNames.PART}")) } ?: 0L
        repo.setProgress(id, maxOf(bytes, reported ?: 0L))
    }

    private fun stopRunning(reason: StopReason) {
        running.values.forEach { it.stop(reason) }
    }

    /**
     * [id]'s run, or null when none runs. A run that is [Running.ending] already took its stop: this waits until its
     * state is written, so the caller acts on the row it left (a resume while a pause's PAUSED is written queues it).
     */
    private suspend fun runOf(id: Long): Running? {
        while (true) {
            val entry = running[id] ?: return null
            if (!entry.ending) return entry
            entry.ended.await()
        }
    }

    private suspend fun pauseNow(id: Long) {
        runOf(id)?.let {
            it.stop(StopReason.PAUSE)
            return
        }
        val row = repo.download(id) ?: return
        if (row.status == DownloadStatus.QUEUED) repo.setStatus(id, DownloadStatus.PAUSED)
    }

    /** Queues a paused [id] again, or one whose pause is still being written; its failed attempts start over. */
    private suspend fun resumeNow(id: Long) {
        runOf(id)?.let {
            if (it.reason == StopReason.PAUSE) {
                it.reason = StopReason.REQUEUE
                repo.setAttempts(id, 0)
            }
            return
        }
        val row = repo.download(id) ?: return
        if (row.status != DownloadStatus.PAUSED) return
        repo.setAttempts(id, 0)
        repo.setStatus(id, DownloadStatus.QUEUED)
    }

    /** A user action: a queue stopped by failures or a full disk runs again. */
    private fun userActed() {
        failureStreak = 0
        stopped.value = null
    }

    // ---- Owners and deleting ------------------------------------------------------------------------------------------

    private suspend fun disown(profileId: String, id: Long) {
        if (repo.removeOwner(id, profileId).isEmpty()) purge(id)
    }

    /** Deletes [id] and its files: now, or once its run stopped. */
    private suspend fun purge(id: Long) {
        if (!purging.add(id)) return
        val entry = running[id]
        if (entry != null) {
            entry.stop(StopReason.DELETE)
            return
        }
        deleteFiles(id)
    }

    /**
     * Deletes [id]'s files (video, part, subtitles; its meta's artwork with its meta's last download) and its row. A
     * download with files whose location isn't there (a card taken out, a picked folder's drive gone), or whose files
     * can't all be deleted (no permission, a volume gone read-only, a file another process holds), keeps its row,
     * without owners and so on no screen, until a later purge deletes them (the next change to the downloads, the next
     * launch): its files are never left on the volume with nothing that knows them. The purge ends either way: one that
     * throws leaves the row, which the next change to the downloads purges again.
     */
    private suspend fun deleteFiles(id: Long) {
        try {
            val row = repo.download(id) ?: return
            val path = row.relativePath
            if (path != null) {
                val directory = env.locationPath(row.locationId)?.takeIf { env.io { exists(Path(it)) } }
                if (directory == null) {
                    Log.i(TAG) { "download $id: its location isn't there, so its files are deleted once it is" }
                    return
                }
                val deleted = withContext(env.io) {
                    val video = child(directory, path)
                    val files = listOf(
                        env.files.delete(video),
                        env.files.deletePart(video),
                        env.files.deleteRecursively(child(directory, DownloadNames.subtitlesDirectory(path))),
                    ).all { it }
                    env.files.deleteEmptyParents(video.parent, Path(directory))
                    files
                }
                if (!deleted) {
                    Log.w(TAG) { "download $id: some of its files can't be deleted, so it stays until they are" }
                    return
                }
            }
            val meta = repo.delete(id)
            val artworkDirectory = meta?.let { env.locationPath(it.locationId) }
            if (meta != null && artworkDirectory != null) {
                withContext(env.io) { meta.artwork.values.forEach { env.files.delete(child(artworkDirectory, it)) } }
            }
        } finally {
            purging -= id
        }
    }

    private suspend fun discardPart(row: DownloadRow) {
        val directory = env.locationPath(row.locationId) ?: return
        val path = row.relativePath ?: return
        withContext(env.io) { env.files.deletePart(child(directory, path)) }
        repo.resetFile(row.id)
    }

    /** At launch: part files (and what a platform keeps next to them) no download refers to. */
    private suspend fun sweepOrphanParts() {
        val rows = repo.downloads()
        val known = rows.mapNotNull { row -> row.relativePath?.let { row.locationId to it } }.toSet()
        val locations = (withContext(env.io) { env.platform.locations() }.map { it.id to it.path } +
            rows.mapNotNull { row -> DownloadLocation.folderPath(row.locationId)?.let { row.locationId to it } }).distinct()
        val suffixes = listOf(DownloadNames.PART) + DownloadNames.PART_SIDECARS
        withContext(env.io) {
            for ((id, directory) in locations) {
                val root = Path(directory).toString()
                for (top in listOf(DownloadNames.MOVIES, DownloadNames.SHOWS)) {
                    for (file in env.files.findFiles(child(directory, top), suffixes)) {
                        val relative = file.toString().removePrefix(root).trimStart('/', '\\').replace('\\', '/')
                        val video = suffixes.firstOrNull { relative.endsWith(it) }?.let { relative.removeSuffix(it) } ?: continue
                        if ((id to video) !in known) env.files.delete(file)
                    }
                }
            }
        }
    }

    // ---- Queueing ------------------------------------------------------------------------------------------------------

    /** Queues [videoId] of [meta] ([DownloadRepository.enqueue], whose [keepSavedMeta] it passes on). */
    private suspend fun insert(
        profileId: String,
        meta: MetaItem,
        videoId: String,
        choice: StreamChoice?,
        keepSynced: Boolean,
        keepSavedMeta: Boolean = false,
    ): Enqueued {
        val video = meta.video(videoId)
        val episode = video?.seriesInfo
        val new = NewDownload(
            type = meta.type,
            metaId = meta.id,
            videoId = videoId,
            title = meta.name,
            year = meta.preview.year,
            episodeTitle = video?.title?.trim()?.takeIf { it.isNotEmpty() && !videoIsTitle(meta.id, videoId) },
            season = episode?.season,
            episode = episode?.episode,
            poster = meta.preview.poster,
            thumbnail = video?.thumbnail,
            stream = choice,
            locationId = mutableLocations.value.selected,
            keepSynced = keepSynced,
        )
        val result = repo.enqueue(profileId, new, meta, keepSavedMeta)
        if (result is Enqueued.Owned && result.discarded != null) {
            val directory = result.locationId?.let { env.locationPath(it) }
            if (directory != null) withContext(env.io) { env.files.deletePart(child(directory, result.discarded)) }
        }
        return result
    }

    /**
     * Queues [videos] of [meta] for [profileId] with streams picked without asking (see [enqueueEpisodes]), skipping
     * the ones already downloaded or queued (their owners gain [profileId]). Without a [reference] given, the series'
     * last download is it, and while that has no binge group each pick is the next one's, so a season keeps to one
     * release; a release's episode picked elsewhere (not ready there, or missing) leaves the rest in that release.
     * Returns how many it queued with a stream. The meta is saved once: with the first episode queued, or before for a
     * Keep synced run ([runRule]).
     */
    private suspend fun queueEpisodes(profileId: String, meta: MetaItem, videos: List<Video>, reference: StreamChoice?, keepSynced: Boolean): Int {
        if (videos.isEmpty()) return 0
        val addons = env.addons(profileId)
        val settings = env.settings.settings(profileId)
        // A last download whose stream can't be read gives no reference: the picks follow the profile's rules.
        var last = reference ?: (repo.lastWithStream(meta.id)?.stream as? DownloadStream.Picked)?.choice
        var queued = 0
        var keepSavedMeta = keepSynced
        for (video in videos) {
            val existing = repo.byVideo(meta.id, video.id)
            if (existing != null && existing.status != DownloadStatus.FAILED && existing.status != DownloadStatus.CANCELLED) {
                repo.addOwner(existing.id, profileId, byHand = !keepSynced)
                continue
            }
            val choice = picker.stream(profileId, meta, video.id, last, addons, settings)
            insert(profileId, meta, video.id, choice, keepSynced, keepSavedMeta)
            keepSavedMeta = true
            if (choice != null) {
                queued++
                if (reference == null && last?.stream?.behaviorHints?.bingeGroup == null) last = choice
            }
        }
        return queued
    }

    // ---- Extras -------------------------------------------------------------------------------------------------------

    private suspend fun saveExtras(rows: List<DownloadRow>) {
        extrasLock.withLock { extras.save(rows) }
    }

    /** Saves what downloads miss of their extras (the network came back, the launch); a failure waits for the next time. */
    private suspend fun saveMissingExtras() {
        if (!env.network.value.online) return
        logFailures("saving missing extras") { saveExtras(repo.missingExtras()) }
    }

    // ---- Keep synced and auto-remove ----------------------------------------------------------------------------------

    /** A watch was recorded for [metaId] by [profileId]: auto-remove what it watched, then top its rule up. */
    private suspend fun afterWatch(profileId: String, metaId: String) {
        logFailures("auto-remove and Keep synced after a watch") {
            autoRemove(profileId, metaId)
            runRuleOnItsOwn(profileId, metaId, force = true)
        }
    }

    /** With auto-remove on, [profileId] lets go of its completed downloads of [metaId] it has watched (not one playing). */
    private suspend fun autoRemove(profileId: String, metaId: String) {
        if (env.device.value?.downloadAutoRemoveWatched != true) return
        val item = env.library.item(profileId, metaId) ?: return
        val saved = repo.savedMeta(metaId)
        // Without its videos (its saved meta can't be read) a mark can't be matched to a download: none goes.
        if (saved != null && saved.meta == null) return
        val meta = saved?.meta?.takeIf { it.videos.isNotEmpty() }
        val marks = meta?.let { WatchedBitField.of(item.state.watchedVideos, it) }
        for (row in repo.downloads().filter { it.metaId == metaId && profileId in it.owners && it.status == DownloadStatus.COMPLETED }) {
            val watched = if (marks == null || videoIsTitle(metaId, row.videoId)) item.isWatched else marks.isWatched(row.videoId)
            if (!watched) continue
            if (row.id in playing) {
                heldRemovals += profileId to row.id
                continue
            }
            Log.i(TAG) { "auto-remove: download ${row.id} was watched" }
            disown(profileId, row.id)
        }
    }

    /** Every rule, spaced as the network asks ([ruleSpacing]); a failure waits for the next time (launch, network). */
    private suspend fun runRules(force: Boolean) {
        logFailures("running the Keep synced rules") { repo.rules().forEach { runRuleOnItsOwn(it.profileId, it.metaId, force) } }
    }

    /** [runRule] without the user asking: what it queues goes into the profile's [keepSyncedNotice]. */
    private suspend fun runRuleOnItsOwn(profileId: String, metaId: String, force: Boolean) {
        val queued = runRule(profileId, metaId, force) ?: return
        if (queued.episodes == 0) return
        notices.update { all ->
            val before = all[profileId]
            val titles = before?.titles.orEmpty().let { if (queued.title in it) it else it + queued.title }
            all + (profileId to KeepSyncedNotice((before?.episodes ?: 0) + queued.episodes, titles))
        }
    }

    /** What a rule's run queued: [episodes] episodes of the series named [title]. */
    private class RuleRun(val episodes: Int, val title: String)

    /**
     * Tops [profileId]'s rule for [metaId] up to its count of unwatched episodes downloaded, in episode order from the
     * library's pointer on, never deleting; the meta is fetched again first (new episodes; the rule keeps its name and
     * poster), else [known] or the saved one. A paused rule doesn't run; unless [force]d, a rule run less than
     * [ruleSpacing] ago waits. Returns what it queued; null when it didn't run.
     */
    private suspend fun runRule(profileId: String, metaId: String, force: Boolean, known: MetaItem? = null): RuleRun? = syncLock.withLock {
        val rule = repo.rule(profileId, metaId)?.takeIf { !it.paused } ?: return@withLock null
        val network = env.network.value
        if (!network.online) return@withLock null
        val now = env.clock.now().toEpochMilliseconds()
        val last = rule.lastRun
        if (!force && last != null && now - last < ruleSpacing(network).inWholeMilliseconds) return@withLock null
        repo.setRuleRun(profileId, metaId, now)
        val meta = fetchMeta(profileId, rule.type, metaId) ?: known ?: repo.savedMeta(metaId)?.meta ?: return@withLock null
        // Paused or stopped while the meta was fetched.
        if (repo.rule(profileId, metaId)?.paused != false) return@withLock null
        repo.setRuleTitle(metaId, meta.name, meta.preview.poster)
        if (repo.hasSavedMeta(metaId)) repo.saveMeta(metaId, meta, mutableLocations.value.selected)
        val item = env.library.item(profileId, metaId)
        val marks = WatchedBitField.of(item?.state?.watchedVideos, meta)
        val rows = repo.downloads().filter { it.metaId == metaId }
        val kept = rows.count { row ->
            profileId in row.owners && row.status != DownloadStatus.FAILED && row.status != DownloadStatus.CANCELLED && !marks.isWatched(row.videoId)
        }
        val target = rule.episodes ?: Int.MAX_VALUE
        var missing = target - kept
        if (missing <= 0) return@withLock RuleRun(0, meta.name)
        val byVideo = rows.associateBy { it.videoId }
        val wanted = mutableListOf<Video>()
        for (video in picker.syncCandidates(meta, item, env.clock.now())) {
            if (missing <= 0) break
            val existing = byVideo[video.id]
            if (existing != null && existing.status != DownloadStatus.CANCELLED) {
                if (profileId !in existing.owners) {
                    repo.addOwner(existing.id, profileId, byHand = false)
                    if (existing.status != DownloadStatus.FAILED) missing--
                }
                continue
            }
            wanted += video
            missing--
        }
        val queued = queueEpisodes(profileId, meta, wanted, reference = null, keepSynced = true)
        wake()
        RuleRun(queued, meta.name)
    }

    /** The meta as [profileId]'s addons answer now, as Detail picks it ([com.edde746.tortuga.addons.AddonAggregator.firstMeta]). */
    private suspend fun fetchMeta(profileId: String, type: String, id: String): MetaItem? =
        withTimeoutOrNull(META_TIMEOUT) { env.aggregator.firstMeta(env.addons(profileId), type, id) }

    // ---- Storage and locations ----------------------------------------------------------------------------------------

    private suspend fun refreshLocations() {
        val list = withContext(env.io) { env.platform.locations() }
        val setting = env.device.value?.downloadLocation
        val selected = setting?.takeIf { id -> list.any { it.id == id } || DownloadLocation.folderPath(id) != null }
            ?: list.firstOrNull()?.id ?: DownloadLocation.INTERNAL
        val folder = DownloadLocation.folderPath(selected)?.takeIf { list.none { it.id == selected } }
        mutableLocations.value = DownloadLocations(if (folder != null) list + DownloadLocation.folder(folder) else list, selected)
        located = true
        locatedFor = setting
    }

    /** The directory of the location [id] as last read ([locations]). */
    private fun locationDirectory(id: String): String? =
        mutableLocations.value.locations.firstOrNull { it.id == id }?.path ?: DownloadLocation.folderPath(id)

    /** What the downloads' subtitles and artwork take, read from the disk, and the free space. */
    private suspend fun refreshStorageNow() {
        val rows = repo.downloads()
        // A meta's artwork is the same on each of its downloads: gone through once.
        val artwork = rows.distinctBy { it.metaId }.flatMap { row -> row.artwork.values.map { (row.artworkLocationId ?: row.locationId) to it } }.distinct()
        usedOnDisk.value = withContext(env.io) {
            val subtitles = rows.sumOf { row ->
                val directory = locationDirectory(row.locationId) ?: return@sumOf 0L
                row.relativePath?.let { env.files.totalSize(child(directory, DownloadNames.subtitlesDirectory(it))) } ?: 0L
            }
            subtitles + artwork.sumOf { (location, path) -> locationDirectory(location)?.let { env.files.size(child(it, path)) } ?: 0L }
        }
        refreshFree()
    }

    private suspend fun refreshFree() {
        val directory = locationDirectory(mutableLocations.value.selected)
        freeBytes.value = directory?.let { withContext(env.io) { env.platform.freeBytes(it) } }
        updateStorage()
    }

    private fun updateStorage() {
        val live = transfers.live.value
        val used = rows.value.orEmpty().sumOf { row ->
            when (row.status) {
                DownloadStatus.COMPLETED -> row.totalBytes ?: row.downloadedBytes
                else -> live[row.id]?.bytes ?: row.downloadedBytes
            }
        }
        mutableStorage.value = DownloadStorage(used + usedOnDisk.value, freeBytes.value)
    }

    private suspend fun download(id: Long): Download? {
        val row = repo.downloads().firstOrNull { it.id == id } ?: return null
        val network = env.network.value
        return row.toDownload(transfers.live.value[id], network.online && network.metered && env.wifiOnly(), mutableLocations.value)
    }

    private fun DownloadRow.toDownload(live: LiveProgress?, waitingForWifi: Boolean, locations: DownloadLocations): Download {
        fun local(url: String?): String? = savedUrl(url, artwork, artworkLocationId, locations)
        return Download(
            id = id,
            type = type,
            metaId = metaId,
            videoId = videoId,
            title = title,
            episodeTitle = episodeTitle,
            season = season,
            episode = episode,
            poster = local(poster),
            thumbnail = local(thumbnail),
            status = status,
            failure = failure.takeIf { status == DownloadStatus.FAILED },
            bytes = live?.bytes ?: downloadedBytes,
            totalBytes = totalBytes ?: live?.totalBytes,
            bytesPerSecond = live?.bytesPerSecond?.takeIf { status == DownloadStatus.DOWNLOADING },
            waitingForWifi = waitingForWifi && status == DownloadStatus.QUEUED,
            addedAt = addedAt,
            completedAt = completedAt,
            owners = owners,
            keepSyncedEpisode = keepSynced,
        )
    }

    /** [url] as saved on the device: a `file://` URL where [artwork] has it under [locationId]'s directory, else [url]. */
    private fun savedUrl(url: String?, artwork: Map<String, String>, locationId: String?, locations: DownloadLocations): String? {
        val path = url?.let(artwork::get) ?: return url
        val directory = locationId?.let { id -> locations.locations.firstOrNull { it.id == id }?.path ?: DownloadLocation.folderPath(id) }
        return directory?.let { fileUrl(child(it, path).toString()) } ?: url
    }

    /** A run of the queue: its coroutine and, once asked to stop, why; then its end being written ([finish]). */
    private class Running {
        var job: Job? = null
        var reason: StopReason? = null

        /** [finish] took [reason] and writes what the run left; [ended] completes once written and out of [running]. */
        var ending = false
        val ended = CompletableDeferred<Unit>()

        /** Stops the run for [reason], unless it is stopping for one as strong already ([StopReason]'s order). */
        fun stop(reason: StopReason) {
            val current = this.reason
            if (current != null && current >= reason) return
            this.reason = reason
            job?.cancel()
        }
    }

    /**
     * Why a run stops, weakest first: a stronger one replaces a weaker one while the run unwinds, never the other way
     * (a pause then the network's requeue stays paused). A resume turns a pause into a requeue itself ([resumeNow]).
     */
    private enum class StopReason { REQUEUE, DISK_FULL, PAUSE, CANCEL, DELETE }

    private enum class QueueStop { FAILURES, DISK_FULL }

    internal companion object {
        const val MAX_FAILURE_STREAK = 3
        private val META_TIMEOUT: Duration = 30.seconds
        private val REATTACH_TIMEOUT: Duration = 30.seconds
        private const val TAG = "downloads"

        /** How long a Keep synced rule waits between runs (except right after a watch). */
        fun ruleSpacing(network: NetworkState): Duration = if (network.metered) 3.hours else 30.minutes

        /**
         * The watch state moved on from [old]: a watched mark changed, or the watched threshold was crossed; an item
         * seen for the first time counts when it has a mark (marked watched without ever playing).
         */
        fun watchedMore(old: WatchState?, new: WatchState): Boolean = when (old) {
            null -> new.watched != null || new.timesWatched > 0
            else -> new.watched != old.watched || new.timesWatched > old.timesWatched || new.flaggedWatched > old.flaggedWatched
        }
    }
}

/** [locations] where downloads can go now, and the [selected] one's id (new downloads go there). */
data class DownloadLocations(val locations: List<DownloadLocation>, val selected: String)

/**
 * Sets up downloads on this graph over [platform] ([AppGraph.downloads]): the queue picks up where the last process left
 * it (transfers still running are reattached, the rest queued again) and runs. Call once, on the main thread, after the
 * graph is built; never on Apple TV.
 */
fun AppGraph.installDownloads(platform: DownloadPlatform): DownloadManager =
    installDownloads(platform, Dispatchers.IO, SystemFileSystem, TimeSource.Monotonic)

/** [installDownloads] with its file operations on [io] over [fileSystem] and its speeds measured with [timeSource] (tests). */
internal fun AppGraph.installDownloads(
    platform: DownloadPlatform,
    io: CoroutineContext,
    fileSystem: FileSystem,
    timeSource: TimeSource,
): DownloadManager {
    check(downloads == null) { "downloads are already installed" }
    val env = DownloadEnv(
        repo = data.downloads,
        platform = platform,
        files = DownloadFiles(fileSystem),
        resolver = streamResolver,
        aggregator = aggregator,
        subtitles = subtitleResolver,
        http = http,
        settings = data.settings,
        deviceSettings = data.deviceSettings,
        library = data.library,
        streamHistory = data.streamHistory,
        addons = { profileId -> addonRegistry(profileId).load() },
        network = network,
        userAgent = TortugaHttpClient.userAgent(appVersion),
        io = io,
        clock = clock,
    )
    val manager = DownloadManager(env, scope, timeSource)
    downloads = manager
    manager.start()
    return manager
}
