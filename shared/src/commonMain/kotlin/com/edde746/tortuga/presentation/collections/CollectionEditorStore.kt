package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionFolder
import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.CollectionSources
import com.edde746.tortuga.collections.CollectionTooLargeException
import com.edde746.tortuga.collections.CollectionViewMode
import com.edde746.tortuga.collections.CollectionsRepository
import com.edde746.tortuga.collections.note
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.title
import com.edde746.tortuga.presentation.board.trackerRowTitle
import com.edde746.tortuga.presentation.board.typeLabelInText
import com.edde746.tortuga.presentation.settings.SettingChoiceOption
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.trakt.TraktLists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The collection editor ([CollectionEditorUiState]) of [profileId]'s collection [collectionId], or of a new one (null).
 * Edits stay in the editor until [save] validates them (a title, at least one folder, each folder with a title and a
 * source, small enough to sync) and stores the collection ([CollectionsRepository.save]: a new one goes first on
 * Home); then it calls [close]. [back] from the editor itself closes it too, asking first whether to discard the
 * changes when the draft differs from the collection as loaded ([CollectionEditorUiState.discard]).
 *
 * The source picker ([openPicker]) offers the enabled [installedAddons]' catalogs that need no filter but a genre (one
 * choice per genre where the catalog requires one), the connected [trackerSources]' lists (read when the picker opens),
 * and a public list by its link: Trakt's where the build has Trakt ([traktLists], no account needed), MDBList's while
 * MDBList is connected (its key reads it). A link is looked up for its list's name before the list is added.
 *
 * Call everything on the main thread; [release] ends it.
 */
class CollectionEditorStore internal constructor(
    private val profileId: String,
    private val collectionId: String?,
    private val collections: CollectionsRepository,
    private val installedAddons: Flow<List<Descriptor>>,
    private val trackerSources: Flow<List<TrackerCatalogSource>>,
    private val traktLists: TraktLists?,
    private val close: () -> Unit,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** A tracker's lists in the picker: null while they are read; [failed] when they couldn't be. */
    private class TrackerLists(val source: TrackerCatalogSource) {
        var lists: List<TrackerCatalog>? = null
        var failed = false
    }

    @OptIn(ExperimentalUuidApi::class)
    private val id = collectionId ?: Uuid.random().toString()
    private var loaded = collectionId == null
    private var title = ""
    private var viewMode = CollectionViewMode.TABS
    private var showAllTab = true
    private var shape = PosterShape.POSTER
    private var backdropUrl: String? = null
    private var folders: List<CollectionFolder> = emptyList()
    private var saving = false
    private var error: String? = null

    /** The draft as loaded (a new collection's: empty): Back asks before dropping a draft that differs. */
    private var baseline = draft()
    private var discardOpen = false

    /** The folder whose page is open, by index in [folders]. */
    private var folderIndex: Int? = null
    private var pickerOpen = false
    private var pickerTrackers: List<TrackerLists> = emptyList()
    private var pickerJob: Job? = null

    /** The service whose list-by-link form is open; [urlResolving] while its link is looked up, [urlError] why it gave no list. */
    private var urlService: TrackerService? = null
    private var urlResolving = false
    private var urlError: String? = null

    private var addons: List<Descriptor> = emptyList()
    private var trackers: List<TrackerCatalogSource> = emptyList()

    /** The picker's choices as last shown, by key. */
    private var choices: Map<String, CollectionSource> = emptyMap()

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<CollectionEditorUiState> = mutableState.asStateFlow()

    init {
        if (collectionId != null) {
            scope.launch {
                val collection = collections.collections(profileId).firstOrNull { it.id == collectionId }
                    ?: return@launch close()
                title = collection.title
                viewMode = collection.viewMode
                showAllTab = collection.showAllTab
                shape = collection.shape
                backdropUrl = collection.backdropUrl
                folders = collection.folders
                loaded = true
                baseline = draft()
                publish()
            }
        }
        scope.launch {
            installedAddons.collect {
                addons = it
                publish()
            }
        }
        scope.launch {
            trackerSources.collect {
                trackers = it
                if (pickerOpen) readTrackerLists()
                publish()
            }
        }
    }

    fun setTitle(text: String) = change { title = text }

    /** Chooses [optionId] of the choice row [rowId]: the view ([CollectionEditorUiState.viewMode]), the tiles' shape. */
    fun choose(rowId: String, optionId: String) = change {
        when (rowId) {
            VIEW_MODE -> viewMode = CollectionViewMode.valueOf(optionId)
            SHAPE -> shape = PosterShape.valueOf(optionId)
            else -> error("The collection editor has no choice $rowId")
        }
    }

    /** Switches the row [rowId]: the All tab ([CollectionEditorUiState.showAllTab]), a folder's hidden title. */
    fun toggle(rowId: String, checked: Boolean) = change {
        when (rowId) {
            SHOW_ALL_TAB -> showAllTab = checked
            HIDE_TITLE -> editFolder { it.copy(hideTitle = checked) }
            else -> error("The collection editor has no switch $rowId")
        }
    }

    /** Adds a folder at the end and opens its page. */
    @OptIn(ExperimentalUuidApi::class)
    fun addFolder() = change {
        folders = folders + CollectionFolder(Uuid.random().toString(), "", sources = emptyList())
        folderIndex = folders.lastIndex
    }

    /** Opens the page of the folder at [index]. */
    fun openFolder(index: Int) = change {
        if (index in folders.indices) folderIndex = index
    }

    fun moveFolderUp(index: Int) = moveFolderTo(index, index - 1)

    fun moveFolderDown(index: Int) = moveFolderTo(index, index + 1)

    /** Moves the folder at [index] to [to] (dragging). */
    fun moveFolderTo(index: Int, to: Int) = change { folders = folders.moved(index, to) }

    fun deleteFolder(index: Int) = change {
        if (index !in folders.indices) return@change
        folders = folders.filterIndexed { i, _ -> i != index }
        folderIndex = null
    }

    /** The open folder's title. */
    fun setFolderTitle(text: String) = change { editFolder { it.copy(title = text) } }

    /** The open folder's cover image link; blank for none. */
    fun setCoverUrl(text: String) = change { editFolder { it.copy(coverUrl = text) } }

    fun moveSourceUp(index: Int) = moveSourceTo(index, index - 1)

    fun moveSourceDown(index: Int) = moveSourceTo(index, index + 1)

    /** Moves the open folder's source at [index] to [to] (dragging). */
    fun moveSourceTo(index: Int, to: Int) = change { editFolder { it.copy(sources = it.sources.moved(index, to)) } }

    fun removeSource(index: Int) = change { editFolder { folder -> folder.copy(sources = folder.sources.filterIndexed { i, _ -> i != index }) } }

    /** Opens the source picker over the open folder's page, reading the connected trackers' lists. */
    fun openPicker() {
        if (folderIndex == null) return
        pickerOpen = true
        readTrackerLists()
        publish()
    }

    /** Adds the source [key] ([SourceChoice.key]) to the open folder and closes the picker; a list by link opens its form. */
    fun pick(key: String) {
        if (key.startsWith(URL_PREFIX)) {
            urlService = TrackerService.fromId(key.removePrefix(URL_PREFIX)) ?: return
            urlError = null
            return publish()
        }
        val source = choices[key] ?: return
        change {
            editFolder { folder -> if (source in folder.sources) folder else folder.copy(sources = folder.sources + source) }
            pickerOpen = false
        }
    }

    /** Looks up the list [url] links to on the open form's service, then adds it to the open folder. */
    fun submitListUrl(url: String) {
        val service = urlService ?: return
        if (urlResolving) return
        val text = T.collections.editor.picker
        val name = serviceName(service)
        val source = if (service == TrackerService.TRAKT) traktLists else trackers.firstOrNull { it.service == service }
        if (source == null) return
        urlResolving = true
        urlError = null
        publish()
        scope.launch {
            val list = try {
                source.publicList(url.trim()).also { if (it == null) urlError = text.notAList(name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrackerException) {
                Log.w(TAG, e) { "${service.id} list lookup failed" }
                urlError = if ((e as? TrackerException.Api)?.status == 404) text.notFound(name) else text.unreachable(name)
                null
            }
            urlResolving = false
            if (list != null) {
                val added = CollectionSource.TrackerList(service.id, list.id, TrackerCatalog.Kind.PUBLIC_LIST, list.type, list.name)
                editFolder { folder -> if (added in folder.sources) folder else folder.copy(sources = folder.sources + added) }
                urlService = null
                pickerOpen = false
            }
            publish()
        }
    }

    /**
     * Back: closes the list-by-link form, else the picker, else the folder's page, else the discard question (keeping
     * the draft), else the editor: at once when nothing changed, else after asking ([CollectionEditorUiState.discard]).
     */
    fun back() {
        when {
            urlService != null -> urlService = null
            pickerOpen -> pickerOpen = false
            folderIndex != null -> folderIndex = null
            discardOpen -> discardOpen = false
            loaded && !saving && draft() != baseline -> discardOpen = true
            else -> return close()
        }
        publish()
    }

    /** The discard question's Discard: drops the draft and closes the editor. */
    fun discard() {
        if (!discardOpen) return
        discardOpen = false
        close()
    }

    /** The discard question's Keep editing: back to the draft. */
    fun keepEditing() {
        discardOpen = false
        publish()
    }

    /** Checks the collection and stores it, then closes the editor; [CollectionEditorUiState.error] says what's wrong. */
    fun save() {
        if (!loaded || saving) return
        val text = T.collections.editor
        val collection = draft()
        error = when {
            collection.title.isEmpty() -> text.noTitle
            collection.folders.isEmpty() -> text.noFolder
            else -> collection.folders.withIndex().firstNotNullOfOrNull { (i, folder) ->
                when {
                    folder.title.isEmpty() -> text.folderNoTitle((i + 1).toString())
                    folder.sources.isEmpty() -> text.folderNoSource(isolate(folder.title))
                    else -> null
                }
            }
        }
        if (error != null) return publish()
        saving = true
        publish()
        scope.launch {
            try {
                collections.save(profileId, collection)
                saving = false
                return@launch close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CollectionTooLargeException) {
                error = e.message
            } catch (e: Exception) {
                Log.e(TAG, e) { "saving a collection failed (${e::class.simpleName})" }
                error = text.saveFailed
            }
            saving = false
            publish()
        }
    }

    fun release() {
        scope.cancel()
    }

    /** Applies [edit] unless the collection isn't read yet or is being saved; the last refusal goes. */
    private fun change(edit: () -> Unit) {
        if (!loaded || saving) return
        edit()
        error = null
        publish()
    }

    /** The collection as it would be saved: titles trimmed, a blank cover link none. */
    private fun draft() = Collection(
        id = id,
        title = title.trim(),
        viewMode = viewMode,
        showAllTab = showAllTab,
        shape = shape,
        backdropUrl = backdropUrl,
        folders = folders.map { it.copy(title = it.title.trim(), coverUrl = it.coverUrl?.trim()?.takeIf(String::isNotEmpty)) },
    )

    /** Replaces the open folder with what [edit] makes of it. */
    private fun editFolder(edit: (CollectionFolder) -> CollectionFolder) {
        val index = folderIndex ?: return
        folders = folders.mapIndexed { i, folder -> if (i == index) edit(folder) else folder }
    }

    /** Reads the connected trackers' lists for the picker, keeping those read for a tracker still connected. */
    private fun readTrackerLists() {
        val previous = pickerTrackers
        pickerTrackers = trackers.map { source -> previous.firstOrNull { it.source === source && !it.failed } ?: TrackerLists(source) }
        pickerJob?.cancel()
        pickerJob = scope.launch {
            for (tracker in pickerTrackers.filter { it.lists == null }) {
                try {
                    tracker.lists = tracker.source.catalogs().distinctBy { it.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "${tracker.source.service.id} lists failed" }
                    tracker.failed = true
                }
                publish()
            }
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): CollectionEditorUiState {
        val text = T.collections.editor
        val home = T.settings.home
        return CollectionEditorUiState(
            heading = if (collectionId == null) text.newHeading else text.editHeading,
            loaded = loaded,
            title = title,
            viewMode = SettingChoiceRow(
                VIEW_MODE, text.view, text.viewHint,
                listOf(
                    CollectionViewMode.TABS to text.viewTabs,
                    CollectionViewMode.ROWS to text.viewRows,
                    CollectionViewMode.FOLLOW_APP to text.viewFollow,
                ).map { (mode, label) -> SettingChoiceOption(mode.name, label, mode == viewMode) },
            ),
            showAllTab = SettingToggleRow(SHOW_ALL_TAB, text.allTab, text.allTabHint, showAllTab),
            shape = SettingChoiceRow(
                SHAPE, text.shape, null,
                listOf(PosterShape.POSTER to text.shapePoster, PosterShape.LANDSCAPE to text.shapeLandscape, PosterShape.SQUARE to text.shapeSquare)
                    .map { (option, label) -> SettingChoiceOption(option.name, label, option == shape) },
            ),
            folders = folders.mapIndexed { i, folder ->
                val name = isolate(folder.title.ifBlank { text.newFolder })
                EditorFolderRow(
                    id = folder.id,
                    title = folder.title,
                    detail = text.sources(folder.sources.size),
                    canMoveUp = i > 0,
                    canMoveDown = i < folders.lastIndex,
                    moveUpLabel = home.moveUpNamed(name),
                    moveDownLabel = home.moveDownNamed(name),
                    dragLabel = home.dragToReorderNamed(name),
                    deleteLabel = text.deleteNamed(name),
                    moreLabel = T.detail.moreActionsNamed(name),
                )
            },
            saving = saving,
            error = error,
            folder = folderIndex?.let { folders.getOrNull(it) }?.let(::folderPage),
            picker = if (pickerOpen) picker() else null,
            discard = if (discardOpen) DiscardQuestion(text.discardTitle, text.discard, text.keepEditing) else null,
        )
    }

    private fun folderPage(folder: CollectionFolder): FolderEditorUiState {
        val text = T.collections.editor
        val home = T.settings.home
        val states = folder.sources.map { CollectionSources.resolve(it, addons, trackers, traktLists) }
        val titles = sourceTitles(folder.sources.zip(states))
        return FolderEditorUiState(
            heading = folder.title.ifBlank { text.newFolder },
            title = folder.title,
            coverUrl = folder.coverUrl.orEmpty(),
            hideTitle = SettingToggleRow(HIDE_TITLE, text.hideTitle, text.hideTitleHint, folder.hideTitle),
            sources = folder.sources.mapIndexed { i, source ->
                val name = isolate(titles[i])
                EditorSourceRow(
                    title = titles[i],
                    detail = sourceOrigin(source, states[i]),
                    note = states[i].note,
                    canMoveUp = i > 0,
                    canMoveDown = i < folder.sources.lastIndex,
                    moveUpLabel = home.moveUpNamed(name),
                    moveDownLabel = home.moveDownNamed(name),
                    dragLabel = home.dragToReorderNamed(name),
                    removeLabel = text.removeNamed(name),
                    moreLabel = T.detail.moreActionsNamed(name),
                )
            },
        )
    }

    /** The picker's groups; [choices] follows them. */
    private fun picker(): SourcePickerUiState {
        val text = T.collections.editor.picker
        val have = folderIndex?.let { folders.getOrNull(it) }?.sources.orEmpty().toSet()
        val choices = LinkedHashMap<String, CollectionSource>()

        val addonChoices = addons.flatMapIndexed { a, addon ->
            addon.manifest.catalogs.flatMapIndexed { c, catalog ->
                addonSources(addon, catalog).mapIndexed { g, (title, source) ->
                    val key = "addon/$a/$c/$g"
                    choices[key] = source
                    SourceChoice(key, title, addon.manifest.name, source in have)
                }
            }
        }
        val trackerChoices = pickerTrackers.flatMap { tracker ->
            val service = tracker.source.service
            tracker.lists.orEmpty().map { list ->
                val key = "tracker/${service.id}/${list.id}"
                val source = CollectionSource.TrackerList(service.id, list.id, list.kind, list.type, list.name)
                choices[key] = source
                SourceChoice(key, trackerRowTitle(list), serviceName(service), source in have)
            }
        }
        val byLink = listOfNotNull(
            TrackerService.TRAKT.takeIf { traktLists != null },
            TrackerService.MDBLIST.takeIf { trackers.any { it.service == TrackerService.MDBLIST } },
        ).map { service -> SourceChoice("$URL_PREFIX${service.id}", text.byLink(serviceName(service)), serviceName(service), added = false) }
        this.choices = choices

        val service = urlService
        return SourcePickerUiState(
            groups = listOf(
                SourcePickerGroup(text.addons, addonChoices, loading = false, notes = if (addonChoices.isEmpty()) listOf(text.noAddons) else emptyList()),
                SourcePickerGroup(
                    text.trackers,
                    trackerChoices + byLink,
                    loading = pickerTrackers.any { it.lists == null && !it.failed },
                    notes = pickerTrackers.filter { it.failed }.map { text.listsFailed(serviceName(it.source.service)) } +
                        if (trackers.isEmpty() && byLink.isEmpty()) listOf(text.noTrackers) else emptyList(),
                ),
            ),
            urlForm = service?.let {
                ListUrlForm(
                    title = text.byLink(serviceName(it)),
                    hint = if (it == TrackerService.TRAKT) text.traktHint else text.mdblistHint,
                    addLabel = text.add,
                    resolving = urlResolving,
                    error = urlError,
                )
            },
        )
    }

    /**
     * [catalog] of [addon] as sources with their titles: none when it needs a filter other than a genre (a search, a
     * date); one per genre when it requires one; else one, without a genre.
     */
    private fun addonSources(addon: Descriptor, catalog: ManifestCatalog): List<Pair<String, CollectionSource>> {
        val required = catalog.extra.filter { it.isRequired }
        if (required.any { it.name != Extras.GENRE }) return emptyList()
        val name = catalog.title
        fun source(genre: String?) = CollectionSource.AddonCatalog(
            addonId = addon.manifest.id, type = catalog.type, catalogId = catalog.id, genre = genre,
            addonName = addon.manifest.name, catalogName = name,
        )
        val title = T.browse.rowTitle(isolate(name), typeLabelInText(catalog.type))
        if (required.isEmpty()) return listOf(title to source(null))
        return catalog.extraProp(Extras.GENRE)?.options.orEmpty().map { genre -> T.browse.rowTitle(isolate(title), genre) to source(genre) }
    }

    /** [this] with the item at [from] moved to [to] (kept within bounds); the same list when either is out of it. */
    private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from !in indices) return this
        val target = to.coerceIn(0, lastIndex)
        if (target == from) return this
        return toMutableList().apply { add(target, removeAt(from)) }
    }

    private companion object {
        const val TAG = "collectionEditor"
        const val VIEW_MODE = "viewMode"
        const val SHOW_ALL_TAB = "showAllTab"
        const val SHAPE = "shape"
        const val HIDE_TITLE = "folder.hideTitle"
        const val URL_PREFIX = "url/"
    }
}
