package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.DownloadStorage
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.session.localPlayRequest
import com.edde746.tortuga.presentation.players.playWithMenu
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Downloads screen's store (Library's Downloads section): [profileId]'s downloads on this device as they change,
 * grouped by title ([DownloadRows]), with live progress and speed, the storage used and free, and the actions on them.
 *
 * - A row's press ([open]) plays a completed download from the device, opens the Streams sheet in download mode for
 *   an episode no stream could be picked for, and opens the title otherwise. [perform] runs one of its
 *   [DownloadRow.actions] at once; one that fails says so ([DownloadsUiState.notice]), as do Pause all, Resume all and a
 *   group's delete.
 * - A group's delete ([deleteGroup]) deletes several downloads, so it asks first ([DownloadsUiState.deleteDialog]):
 *   [confirmDeleteGroup] deletes them, [dismissDeleteGroup] keeps them.
 * - A completed download whose file is gone fails as it is played (Play, a press, "Play with…"), its row saying why
 *   (its location unavailable, its file missing) and offering Retry and Delete; nothing opens
 *   ([DownloadManager.localVideo]'s `failIfGone`).
 * - A download plays with the meta saved with it ([DownloadManager.savedMeta]), so it plays offline.
 * - Offline ([network]), queued downloads say they wait for a connection ([DownloadsUiState.offlineNote]).
 * - Player apps ([playerApps], where other apps can open the files): a completed download's row offers "Play with…"
 *   ([DownloadAction.PLAY_WITH]) Tortuga or one of them for this play ([playWith]).
 * - [cacheWording]: failures may speak of instant (cached) streams; Apple builds never do.
 * - While the profile has Keep synced rules, a row leads to their screen ([DownloadsUiState.keepSynced],
 *   [openKeepSynced]).
 *
 * Call everything on the main thread; [release] ends it.
 */
class DownloadsStore internal constructor(
    private val profileId: String,
    private val downloads: DownloadManager,
    private val network: StateFlow<NetworkState>,
    private val cacheWording: Boolean,
    private val host: DownloadsHost,
    parentScope: CoroutineScope,
    /** This device's player apps as they're found, where they can open the downloads' files; null otherwise. */
    private val playerApps: Flow<List<PlayerApp>>? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var list: List<Download> = emptyList()
    private var listKnown = false
    private var storage: DownloadStorage? = null
    private var online = network.value.online

    /** This device's player apps, for "Play with…". */
    private var players: List<PlayerApp> = emptyList()
    private var notice: String? = null

    /** How many Keep synced rules the profile has. */
    private var rules = 0

    /** The group [deleteGroup] asks about; null while nothing is asked. */
    private var deleting: String? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<DownloadsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            downloads.downloads(profileId).collect {
                list = it
                listKnown = true
                publish()
            }
        }
        scope.launch {
            downloads.storage.collect {
                storage = it
                publish()
            }
        }
        scope.launch {
            network.collect {
                online = it.online
                publish()
            }
        }
        playerApps?.let { found ->
            scope.launch {
                found.collect {
                    players = it
                    publish()
                }
            }
        }
        scope.launch {
            downloads.keepSyncedRules(profileId).collect {
                rules = it.size
                publish()
            }
        }
        downloads.refreshStorage()
    }

    /** A press on the row [id]: plays it when downloaded, picks a stream for an episode without one, else opens its title. */
    fun open(id: Long) {
        val download = find(id) ?: return
        when {
            download.status == DownloadStatus.COMPLETED -> play(download)
            download.needsStream -> pickStream(download)
            else -> host.openMeta(download.type, download.metaId)
        }
    }

    /**
     * Runs [action] (one of [DownloadRow.actions], but [DownloadAction.PLAY_WITH], whose pick [playWith] takes) on the
     * download [id].
     */
    fun perform(id: Long, action: DownloadAction) {
        val download = find(id) ?: return
        when (action) {
            DownloadAction.PLAY -> play(download)
            DownloadAction.PLAY_WITH -> error("Play with… is a submenu of players: playWith(id, playerId) plays the one picked")
            DownloadAction.PAUSE -> showFailure(downloads.pause(id), T.downloads.actionFailed)
            DownloadAction.RESUME -> showFailure(downloads.resume(id), T.downloads.actionFailed)
            DownloadAction.RETRY -> showFailure(downloads.retry(id), T.downloads.actionFailed)
            DownloadAction.PICK_STREAM -> pickStream(download)
            DownloadAction.CANCEL -> showFailure(downloads.cancel(id), T.downloads.actionFailed)
            DownloadAction.DELETE -> showFailure(downloads.delete(profileId, id), T.downloads.actionFailed)
            DownloadAction.OPEN_DETAIL -> host.openMeta(download.type, download.metaId)
        }
    }

    /** Plays the completed download [id] once in the player [playerId] ([DownloadsUiState.playWith]). */
    fun playWith(id: Long, playerId: String) {
        val download = find(id)?.takeIf { it.status == DownloadStatus.COMPLETED } ?: return
        play(download, playerId)
    }

    /** Asks before deleting every download of the group [key] ([DownloadGroup.key]): [DownloadsUiState.deleteDialog]. */
    fun deleteGroup(key: String) {
        deleting = key
        publish()
    }

    /** Deletes every download of the group [DownloadsUiState.deleteDialog] asked about. */
    fun confirmDeleteGroup() {
        val key = deleting ?: return
        deleting = null
        publish()
        showFailure(downloads.deleteMeta(profileId, key), T.downloads.actionsFailed)
    }

    /** [DownloadsUiState.deleteDialog] was dismissed: the downloads stay. */
    fun dismissDeleteGroup() {
        deleting = null
        publish()
    }

    /** Pauses every queued or running download ([DownloadsUiState.pauseAllText]). */
    fun pauseAll() = showFailure(downloads.pauseAll(profileId), T.downloads.actionsFailed)

    /** Resumes every paused download and queues every failed one again ([DownloadsUiState.resumeAllText]). */
    fun resumeAll() = showFailure(downloads.resumeAll(profileId), T.downloads.actionsFailed)

    /** Opens the Keep synced screen ([DownloadsUiState.keepSynced]). */
    fun openKeepSynced() = host.openKeepSynced()

    /** [DownloadsUiState.notice] was shown: it goes. */
    fun dismissNotice() {
        notice = null
        publish()
    }

    /** Reads the free space again (the screen shown again). */
    fun refreshStorage() = downloads.refreshStorage()

    fun release() {
        scope.cancel()
    }

    private fun find(id: Long): Download? = list.firstOrNull { it.id == id }

    /** Shows [text] as the notice if the download command whose [outcome] it is fails (the manager logged why). */
    private fun showFailure(outcome: Deferred<Unit>, text: String) {
        scope.launch {
            try {
                outcome.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = text
                publish()
            }
        }
    }

    /** Plays [download] from the device: in the player Settings → Player names, or once in [playerId]'s. */
    private fun play(download: Download, playerId: String? = null) {
        scope.launch {
            val local = downloads.localVideo(profileId, download.metaId, download.videoId, failIfGone = true) ?: return@launch
            val meta = downloads.savedMeta(download.type, download.metaId) ?: DownloadRows.minimalMeta(download)
            val request = localPlayRequest(meta, download.videoId, local)
            if (playerId != null) host.playWith(request, playerId) else host.play(request)
        }
    }

    private fun pickStream(download: Download) {
        scope.launch {
            val meta = downloads.savedMeta(download.type, download.metaId) ?: DownloadRows.minimalMeta(download)
            host.openDownloadStreams(meta, download.videoId)
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): DownloadsUiState {
        val text = T.downloads
        val playWith = playWithMenu(players)
        // A group gone meanwhile (deleted on another screen) is no longer asked about.
        val deleted = deleting?.let { key -> list.filter { it.metaId == key } }
        if (deleted?.isEmpty() == true) deleting = null
        return DownloadsUiState(
            title = text.title,
            body = when {
                !listKnown -> DownloadsBody.Loading
                list.isEmpty() -> DownloadsBody.Empty(text.empty)
                else -> DownloadsBody.Groups(DownloadRows.groups(list, online, cacheWording, playsWith = playWith != null))
            },
            offlineNote = if (online) null else text.offline,
            storage = DownloadRows.storage(storage ?: DownloadStorage(usedBytes = 0, freeBytes = null)),
            pauseAllText = text.pauseAll.takeIf { list.any { it.status.active } },
            resumeAllText = text.resumeAll.takeIf { list.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED } },
            playWith = playWith,
            notice = notice,
            keepSynced = KeepSyncedLink(text.keepSynced.title, text.keepSynced.series(rules)).takeIf { rules > 0 },
            deleteDialog = deleted?.takeIf { it.isNotEmpty() }?.let { DownloadRows.deleteDialog(it.first().title, it) },
        )
    }

    companion object {
        /** Its key on the navigator, under the Library's route (test reports read it there). */
        const val KEY: String = "downloads"
    }
}
