package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.KeepSyncedNotice
import com.edde746.tortuga.downloads.KeepSyncedRule
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Keep synced screen's store: [profileId]'s rules as they change, each with its series' name and poster as this
 * device saved them (no addon is asked), how many unwatched episodes it keeps and where its downloads are.
 *
 * - A row's press ([open]) opens the series, where its count is chosen.
 * - [pause] stops a rule from queueing episodes (its downloads carry on); [resume] runs it again at once.
 * - [stop] asks first ([KeepSyncedUiState.stopDialog]); [confirmStop] stops the rule and, when asked, deletes the
 *   downloads it queued, the one playing once it ends ([DownloadManager.stopKeepSynced]); [dismissStop] keeps it.
 * - An action that fails says so ([KeepSyncedUiState.notice]).
 *
 * Call everything on the main thread; [release] ends it.
 */
class KeepSyncedStore internal constructor(
    private val profileId: String,
    private val downloads: DownloadManager,
    private val network: StateFlow<NetworkState>,
    private val host: DownloadsHost,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var rules: List<KeepSyncedRule> = emptyList()
    private var rulesKnown = false
    private var list: List<Download> = emptyList()
    private var online = network.value.online
    private var notice: String? = null

    /** The series [stop] asks about; null while nothing is asked. */
    private var stopping: String? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<KeepSyncedUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            downloads.keepSyncedRules(profileId).collect {
                rules = it
                rulesKnown = true
                publish()
            }
        }
        scope.launch {
            downloads.downloads(profileId).collect {
                list = it
                publish()
            }
        }
        scope.launch {
            network.collect {
                online = it.online
                publish()
            }
        }
    }

    /** A press on [metaId]'s row: opens the series. */
    fun open(metaId: String) {
        val rule = rules.firstOrNull { it.metaId == metaId } ?: return
        host.openMeta(rule.type, rule.metaId)
    }

    /** Pauses [metaId]'s rule: it queues nothing until [resume]d. */
    fun pause(metaId: String) = showFailure(downloads.pauseKeepSynced(profileId, metaId, paused = true))

    /** Resumes [metaId]'s paused rule; it runs at once. */
    fun resume(metaId: String) = showFailure(downloads.pauseKeepSynced(profileId, metaId, paused = false))

    /** Asks before [metaId]'s rule stops: [KeepSyncedUiState.stopDialog]. */
    fun stop(metaId: String) {
        stopping = metaId
        publish()
    }

    /** Stops the rule [KeepSyncedUiState.stopDialog] asked about; [deleteDownloads] deletes the downloads it queued too. */
    fun confirmStop(deleteDownloads: Boolean) {
        val metaId = stopping ?: return
        stopping = null
        publish()
        showFailure(downloads.stopKeepSynced(profileId, metaId, deleteDownloads))
    }

    /** [KeepSyncedUiState.stopDialog] was dismissed: the rule stays. */
    fun dismissStop() {
        stopping = null
        publish()
    }

    /** [KeepSyncedUiState.notice] was shown: it goes. */
    fun dismissNotice() {
        notice = null
        publish()
    }

    fun release() {
        scope.cancel()
    }

    /** Shows the notice if the command whose [outcome] it is fails (the manager logged why). */
    private fun showFailure(outcome: Deferred<Unit>) {
        scope.launch {
            try {
                outcome.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = T.downloads.keepSynced.actionFailed
                publish()
            }
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): KeepSyncedUiState {
        val text = T.downloads.keepSynced
        val bySeries = list.filter { it.status != DownloadStatus.CANCELLED }.groupBy { it.metaId }
        // A rule gone meanwhile (stopped on another screen) is no longer asked about.
        val asked = stopping?.let { metaId -> rules.firstOrNull { it.metaId == metaId } }
        if (asked == null) stopping = null
        return KeepSyncedUiState(
            title = text.title,
            note = text.note.takeIf { rules.isNotEmpty() },
            body = when {
                !rulesKnown -> KeepSyncedBody.Loading
                rules.isEmpty() -> KeepSyncedBody.Empty(text.empty)
                else -> KeepSyncedBody.Rules(rules.map { row(it, bySeries[it.metaId].orEmpty()) })
            },
            notice = notice,
            stopDialog = asked?.let { stopDialog(it, bySeries[it.metaId].orEmpty()) },
        )
    }

    private fun row(rule: KeepSyncedRule, downloads: List<Download>): KeepSyncedRow {
        val text = T.downloads.keepSynced
        val count = rule.rule.episodes
        return KeepSyncedRow(
            metaId = rule.metaId,
            title = rule.title,
            poster = rule.poster,
            countText = count?.let(T.detail.download::keepSyncedCount) ?: T.detail.download.keepSyncedAll,
            statusText = status(rule, downloads),
            paused = rule.paused,
            pauseLabel = if (rule.paused) text.resumeRule(isolate(rule.title)) else text.pauseRule(isolate(rule.title)),
            stopLabel = text.stopRule(isolate(rule.title)),
        )
    }

    /** Paused, else what its series' downloads are doing: on their way, failed, else how many are downloaded. */
    private fun status(rule: KeepSyncedRule, downloads: List<Download>): String {
        val status = T.downloads.status
        val text = T.downloads.keepSynced.status
        if (rule.paused) return status.paused
        val coming = downloads.filter { it.status.active }
        if (coming.isNotEmpty()) {
            return when {
                !online -> status.waitingForNetwork
                coming.all { it.waitingForWifi } -> status.waitingForWifi
                else -> text.downloading(coming.size)
            }
        }
        val failed = downloads.count { it.status == DownloadStatus.FAILED }
        if (failed > 0) return text.failed(failed)
        val done = downloads.count { it.status == DownloadStatus.COMPLETED }
        return if (done > 0) text.downloaded(done) else text.downloadedNone
    }

    private fun stopDialog(rule: KeepSyncedRule, downloads: List<Download>): KeepSyncedStopDialog {
        val text = T.downloads.keepSynced.stop
        val queued = downloads.count { it.keepSyncedEpisode }
        return KeepSyncedStopDialog(
            title = text.title(isolate(rule.title)),
            message = if (queued > 0) text.message(queued) else text.messageNone,
            keepLabel = if (queued > 0) text.keep else text.confirm,
            deleteLabel = text.delete.takeIf { queued > 0 },
            cancelLabel = text.cancel,
        )
    }

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "keepSynced"
    }
}

/** The one line that tells what the rules queued on their own: "Keep synced added 2 episodes of Breaking Bad". */
internal fun KeepSyncedNotice.noticeText(): String {
    val text = T.downloads.keepSynced
    return titles.singleOrNull()?.let { text.notice(episodes, isolate(it)) } ?: text.noticeSeveral(episodes)
}
