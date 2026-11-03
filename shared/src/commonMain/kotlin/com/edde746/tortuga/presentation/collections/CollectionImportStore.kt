package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.collections.CollectionFileException
import com.edde746.tortuga.collections.CollectionImport
import com.edde746.tortuga.collections.CollectionsRepository
import com.edde746.tortuga.collections.ImportSummary
import com.edde746.tortuga.collections.NuvioCollections
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.net.WebUrl
import com.edde746.tortuga.net.readBody
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.trakt.TraktLists
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Imports collections into [profileId] ([CollectionImportUiState]): a file's text by its link ([fetch]: `http` and
 * `https` only, through the app's [http] client, at most [MAX_BYTES] in at most [TIMEOUT]) or as the platform read it
 * from a file ([open]); read as Nuvio's format ([NuvioCollections.decode], on [work]) and summed up against the
 * profile's collections, its enabled [addons], its connected [trackers] and Trakt's public lists where the build has
 * them ([CollectionImport.summary]); [confirm] stores it ([CollectionsRepository.import]). A link or file asked for
 * while another is read replaces it.
 *
 * Call everything on the main thread; [release] ends it.
 */
class CollectionImportStore internal constructor(
    private val profileId: String,
    private val http: HttpClient,
    private val collections: CollectionsRepository,
    private val addons: suspend () -> List<Descriptor>,
    private val trackers: suspend () -> List<TrackerCatalogSource>,
    private val traktLists: TraktLists?,
    /** The platform has file dialogs: Open file is offered. */
    private val offersFiles: Boolean,
    private val work: CoroutineDispatcher,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var step: CollectionImportStep = CollectionImportStep.Idle
    private var summary: ImportSummary? = null
    private var job: Job? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<CollectionImportUiState> = mutableState.asStateFlow()

    /** Reads the file at [url] (an `http` or `https` link). */
    fun fetch(url: String) {
        val link = url.trim()
        if (!WebUrl.isWeb(link)) return show(CollectionImportStep.Failed(T.collections.importing.notWeb))
        read { download(link) }
    }

    /** Reads [text], a file the platform opened. */
    fun open(text: String) = read { text }

    /** The platform couldn't read the file it opened as UTF-8 text. */
    fun openFailed() = show(CollectionImportStep.Failed(T.collections.importing.unreadable))

    /** Imports what the summary shows ([CollectionImportStep.Summary.canImport]). */
    fun confirm() {
        val summary = summary?.takeIf { it.collections.isNotEmpty() && step is CollectionImportStep.Summary } ?: return
        show(CollectionImportStep.Loading)
        job = scope.launch {
            val step = try {
                val result = collections.import(profileId, summary.collections)
                val text = T.collections.importing
                CollectionImportStep.Done(text.imported(result.added.size + result.replaced.size), text.done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "the import failed (${e::class.simpleName})" }
                CollectionImportStep.Failed(T.collections.importing.failed)
            }
            show(step)
        }
    }

    fun release() {
        scope.cancel()
    }

    /** Reads the file's text with [text] (a failure is its message), then sums it up. */
    private fun read(text: suspend () -> String) {
        job?.cancel()
        summary = null
        show(CollectionImportStep.Loading)
        job = scope.launch {
            val step = try {
                val file = withContext(work) { NuvioCollections.decode(text()) }
                val summary = CollectionImport.summary(
                    file, collections.collections(profileId), addons(), trackers(), traktLists,
                ) { collections.fitsSync(profileId, it) }
                this@CollectionImportStore.summary = summary
                summaryStep(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CollectionFileException) {
                CollectionImportStep.Failed(e.message.orEmpty())
            } catch (e: ImportFailure) {
                CollectionImportStep.Failed(e.message.orEmpty())
            }
            show(step)
        }
    }

    /** The body at [url], at most [MAX_BYTES] of it, as UTF-8 text; [ImportFailure] says why there is none. */
    private suspend fun download(url: String): String {
        val text = T.collections.importing
        try {
            return withTimeoutOrNull(TIMEOUT) {
                http.prepareGet(url).execute { response ->
                    if (!response.status.isSuccess()) throw ImportFailure(text.httpError(response.status.value.toString()))
                    val body = response.readBody { it.readBuffer(MAX_BYTES + 1) }
                    if (body.size > MAX_BYTES) throw ImportFailure(text.tooLarge)
                    body.readString()
                }
            } ?: throw ImportFailure(text.timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportFailure) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "the collections link failed (${e::class.simpleName})" }
            throw ImportFailure(text.unreachable)
        }
    }

    private fun summaryStep(summary: ImportSummary): CollectionImportStep.Summary {
        val text = T.collections.importing
        val replaced = summary.replaced.toSet()
        return CollectionImportStep.Summary(
            collections = summary.collections.map {
                val count = it.folders.size
                val replaces = it.id in replaced
                // The first few folders by title: a pack's collection can have a hundred.
                val shown = it.folders.take(SHOWN_FOLDERS).joinToString(T.addons.listSeparator) { folder -> isolate(folder.title) }
                val folders = if (count > SHOWN_FOLDERS) "$shown${T.addons.listSeparator}…" else shown
                CollectionImportRow(it.title, if (replaces) text.rowReplaces(count) else text.rowNew(count), folders, replaces)
            },
            lines = summary.lines,
            importLabel = text.confirm,
            canImport = summary.collections.isNotEmpty(),
        )
    }

    private fun show(step: CollectionImportStep) {
        this.step = step
        mutableState.value = buildState()
    }

    private fun buildState(): CollectionImportUiState {
        val text = T.collections.importing
        return CollectionImportUiState(
            title = text.title,
            note = text.note,
            urlLabel = text.urlLabel,
            urlHint = text.urlHint,
            fetchLabel = text.fetch,
            openFileLabel = if (offersFiles) text.openFile else null,
            step = step,
        )
    }

    /** Why a link gave no file, worded for the user. */
    private class ImportFailure(message: String) : Exception(message)

    companion object {
        /** The largest file a link may give: Kaptain's Mega pack, the largest known, is 0.6 MiB. */
        const val MAX_BYTES: Long = 4L * 1024 * 1024

        /** How long a link may take, its body included. */
        val TIMEOUT: Duration = 30.seconds

        /** The folders a summary row names before "…". */
        private const val SHOWN_FOLDERS = 5

        private const val TAG = "collectionImport"
    }
}
