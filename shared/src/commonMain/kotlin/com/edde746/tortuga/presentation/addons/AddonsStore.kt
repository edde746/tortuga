package com.edde746.tortuga.presentation.addons

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AddonException
import com.edde746.tortuga.addons.AddonRegistry
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.addons.ProfileAddon
import com.edde746.tortuga.addons.message
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.presentation.board.typeLabel
import com.edde746.tortuga.presentation.board.typeOrder
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * The store of Settings' Addons section over the profile's [registry], following
 * stremio-core's `InstalledAddonsWithFilters` (the type filter) and `AddonDetails` (installing from a link, with the
 * fetched manifest shown against the installed copy):
 *
 * - the installed addons in order, reordered by moving up and down or by dragging to an index of the shown list;
 * - each addon's switch: off, it keeps its place and is asked for nothing (the built-in addon stays on);
 * - removal after confirmation, except for the protected built-in addon (Cinemeta);
 * - "Configure", which opens `{base}/configure` through [host];
 * - installing from an `https://…/manifest.json` URL or a `stremio://` link: fetched, previewed, then installed or
 *   updated on confirmation; an addon that must be configured first offers its configuration page instead, and one
 *   that can be configured offers it beside Install; a link followed into the app opens the preview through
 *   [previewInstall]. An addon whose manifest asks for the user's watch activity (`player`, `library`) installs only
 *   once the user ticks that they agree it receives it, as stremio-web asks; an update may go without the tick and
 *   leaves the addon without watch activity;
 * - in the details, whether an addon that asks for watch activity receives it: an addon whose update started asking
 *   gets none until the user allows it there;
 * - details, and the addon's link (its transport URL, a secret) revealed or copied only on request;
 * - with [catalogs] (the build has Browse), the addons that installed addons' addon catalogs list, one catalog and type
 *   at a time, fetched when Browse first shows; picking one previews it.
 *
 * The list follows the registry, so an install or reorder synced from another device shows up here. Opening the store
 * fetches the installed addons' manifests again ([refreshManifests]): an addon reconfigured on its site or updated
 * shows (and is used) as it is now.
 *
 * Call everything on the main thread; [release] ends it.
 */
class AddonsStore internal constructor(
    private val registry: AddonRegistry,
    private val transport: AddonTransport,
    private val storedOrder: suspend () -> List<String>,
    private val host: AddonsHost,
    parentScope: CoroutineScope,
    /** Fetches the installed addons' manifests again, whenever they were last fetched; the store opening calls it. */
    refreshManifests: suspend () -> Unit,
    private val catalogs: AddonAggregator? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var installed: List<Descriptor> = emptyList()

    /** The installed addons' switches and watch-activity agreements, by transport URL. */
    private var choices: Map<String, ProfileAddon> = emptyMap()
    private var loaded = false

    /** Transport URL → row id. Ids are handed out in order of appearance and never reused. */
    private val ids = mutableMapOf<String, String>()
    private var typeFilter: String? = null
    private var detailsUrl: String? = null
    private var linkRevealed = false
    private var removeUrl: String? = null

    /** The stored changes not done yet ([perform]): stored one at a time ([storing]), in the order they came. */
    private var pendingChanges = 0
    private val storing = Mutex()
    private var notice: String? = null

    private var installFlow: Install = Install.Closed

    /** The preview's watch-activity tick ([AddonPreviewUiState.watchActivity]). */
    private var installConsent = false
    private var installJob: Job? = null

    /** An install link opened during an install: previewed once it ends ([previewInstall]). */
    private var waitingLink: TransportUrl? = null

    private var section = AddonsSection.INSTALLED
    private var chosenCatalog: Pair<String, String>? = null
    private var chosenType: String? = null
    private var browseRequest: ResourceRequest? = null
    private var browseItems: List<Descriptor> = emptyList()
    private var browseLoading = false
    private var browseError: String? = null
    private var browseJob: Job? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<AddonsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            registry.installed.collect { profileAddons ->
                val addons = profileAddons.map { it.descriptor }
                installed = addons
                choices = profileAddons.associateBy { it.descriptor.transportUrl }
                if (detailsUrl != null && addons.none { it.transportUrl == detailsUrl }) closeDetailsQuietly()
                // An addon removed meanwhile (here or on another device) isn't asked about again if it comes back.
                if (removeUrl != null && addons.none { it.transportUrl == removeUrl }) removeUrl = null
                if (typeFilter != null && addons.none { typeFilter in it.manifest.types }) typeFilter = null
                publish()
                if (section == AddonsSection.BROWSE) loadBrowse()
            }
        }
        scope.launch {
            try {
                registry.load()
            } catch (e: AddonException) {
                notice = e.error.message
            }
            loaded = true
            publish()
        }
        // In the app's scope, so leaving the screen at once doesn't stop it halfway.
        parentScope.launch { refreshManifests() }
    }

    /** Shows only addons serving [filterId] ([AddonTypeFilter.id]; [ALL_TYPES] for every addon). */
    fun filterType(filterId: String) {
        typeFilter = filterId.takeUnless { it == ALL_TYPES }
        publish()
    }

    fun moveUp(id: String) {
        val shown = visible()
        val index = shown.indexOfFirst { idOf(it) == id }
        if (index > 0) reorder(shown[index], shown[index - 1], before = true)
    }

    fun moveDown(id: String) {
        val shown = visible()
        val index = shown.indexOfFirst { idOf(it) == id }
        if (index >= 0 && index < shown.lastIndex) reorder(shown[index], shown[index + 1], before = false)
    }

    /** Moves the addon to [index] of the shown list (dragging); with a type filter, among the addons shown. */
    fun moveTo(id: String, index: Int) {
        val shown = visible()
        val from = shown.indexOfFirst { idOf(it) == id }
        if (from < 0) return
        val to = index.coerceIn(0, shown.lastIndex)
        if (to != from) reorder(shown[from], shown[to], before = to < from)
    }

    /** Opens the addon's configuration page, if it has one ([AddonRow.configurable]). */
    fun configure(id: String) {
        val addon = find(id) ?: return
        AddonRegistry.configureUrl(addon)?.let(host::openUrl)
    }

    /** Shows the installed addons or Browse ([AddonsUiState.section]); Browse fetches its catalog the first time. */
    fun showSection(section: AddonsSection) {
        if (section == AddonsSection.BROWSE && browse() == null) return
        this.section = section
        publish()
        if (section == AddonsSection.BROWSE) loadBrowse()
    }

    /** Lists Browse's catalog [id] ([AddonBrowseChoice.id] of [AddonBrowseUiState.catalogs]). */
    fun selectBrowseCatalog(id: String) {
        val catalog = browse()?.getOrNull(id.toIntOrNull() ?: return) ?: return
        chosenCatalog = catalog.key
        loadBrowse()
    }

    /** Lists the selected catalog in type [id] ([AddonBrowseChoice.id] of [AddonBrowseUiState.types]). */
    fun selectBrowseType(id: String) {
        chosenType = id
        loadBrowse()
    }

    /** Fetches the selected catalog again, after an error. */
    fun retryBrowse() = loadBrowse(again = true)

    /** Previews the install of Browse's addon [id] ([AddonBrowseItem.id]), as an install link does. */
    fun previewBrowseItem(id: String) {
        val addon = browseItems.getOrNull(id.toIntOrNull() ?: return) ?: return
        TransportUrl.parse(addon.transportUrl)?.let(::previewInstall) ?: invalidLink(typed = "")
    }

    /** Asks before removing the addon; the built-in one can't be removed. */
    fun remove(id: String) {
        val addon = find(id) ?: return
        if (addon.flags.protected) {
            notice = AddonError.Protected.message
        } else {
            removeUrl = addon.transportUrl
            notice = null
        }
        publish()
    }

    fun confirmRemove() {
        val url = removeUrl ?: return
        removeUrl = null
        perform { registry.remove(url) }
    }

    fun dismissRemove() {
        if (removeUrl == null) return
        removeUrl = null
        publish()
    }

    fun dismissNotice() {
        if (notice == null) return
        notice = null
        publish()
    }

    /** Turns the addon on or off ([AddonRow.enabled]); the built-in addon stays on. */
    fun setEnabled(id: String, enabled: Boolean) {
        val addon = find(id) ?: return
        if (choices[addon.transportUrl]?.enabled == enabled) return
        perform { registry.setEnabled(addon.transportUrl, enabled) }
    }

    /** Allows or stops the addon's watch activity ([AddonDetailsUiState.watchActivity]). */
    fun setWatchActivity(id: String, allowed: Boolean) {
        val addon = find(id) ?: return
        if (!addon.manifest.receivesWatchActivity || choices[addon.transportUrl]?.watchActivity == allowed) return
        perform { registry.setWatchActivity(addon.transportUrl, allowed) }
    }

    /** Ticks or clears the preview's agreement to send the addon the user's watch activity ([AddonPreviewUiState.watchActivity]). */
    fun setInstallConsent(agreed: Boolean) {
        if ((installFlow as? Install.Previewing)?.pending?.let(::asksConsent) != true) return
        installConsent = agreed
        publish()
    }

    // ---- Details and the addon's link ----------------------------------------------------------------------------

    fun openDetails(id: String) {
        val addon = find(id) ?: return
        detailsUrl = addon.transportUrl
        linkRevealed = false
        publish()
    }

    fun closeDetails() {
        closeDetailsQuietly()
        publish()
    }

    /** Shows the open addon's link on screen. */
    fun revealLink() {
        if (detailsUrl == null) return
        linkRevealed = true
        publish()
    }

    fun hideLink() {
        if (!linkRevealed) return
        linkRevealed = false
        publish()
    }

    /** Copies the open addon's link without showing it. */
    fun copyLink() {
        detailsUrl?.let(host::copyToClipboard)
    }

    // ---- Installing ----------------------------------------------------------------------------------------------

    fun startInstall() {
        installJob?.cancel()
        installFlow = Install.EnterLink("", null)
        publish()
    }

    fun updateInstallLink(text: String) {
        if (installFlow !is Install.EnterLink) return
        installFlow = Install.EnterLink(text, null)
        publish()
    }

    fun submitInstallLink() {
        val link = (installFlow as? Install.EnterLink)?.link?.takeIf { it.isNotBlank() } ?: return
        fetchPreview(TransportUrl.parse(link) ?: return invalidLink(typed = link), typed = link)
    }

    /**
     * Fetches and previews the addon at [link], from any step: an install link opened from outside the app
     * (`stremio://…/manifest.json`, or its `https://` form). The link isn't put in the state. During an install it
     * waits for the install to end (the last one opened, if several), so the addon the user confirmed is installed.
     */
    fun previewInstall(link: TransportUrl) {
        if (installFlow is Install.Installing) {
            waitingLink = link
            return
        }
        fetchPreview(link, typed = "")
    }

    /** Does the preview's [AddonPreviewUiState.action]; an install waits for the watch-activity tick it asks for. */
    fun confirmInstall() {
        val pending = (installFlow as? Install.Previewing)?.pending ?: return
        if (!canConfirm(pending)) return
        when (action(pending)) {
            is AddonInstallAction.Configure -> TransportUrl.configureUrl(pending.transportUrl)?.let(host::openUrl)
            AddonInstallAction.None -> Unit
            is AddonInstallAction.Install, is AddonInstallAction.Update -> install(pending)
        }
    }

    /**
     * Opens the previewed addon's configuration page ([AddonPreviewUiState.configureLabel]); its Install link comes
     * back as a new preview of the configured addon.
     */
    fun configurePreview() {
        val pending = (installFlow as? Install.Previewing)?.pending ?: return
        TransportUrl.configureUrl(pending.transportUrl)?.let(host::openUrl)
    }

    fun closeInstall() {
        installJob?.cancel()
        waitingLink = null
        installFlow = Install.Closed
        installConsent = false
        publish()
    }

    fun release() {
        scope.cancel()
    }

    /** The link [typed] (empty: one from outside the app) isn't a transport URL: the field says so. */
    private fun invalidLink(typed: String) {
        installJob?.cancel()
        installFlow = Install.EnterLink(typed, AddonError.InvalidTransportUrl.message)
        publish()
    }

    /** [typed]: the link as typed, which the field shows again if it fails; empty for a link opened from outside the app. */
    private fun fetchPreview(transportUrl: TransportUrl, typed: String) {
        installJob?.cancel()
        installFlow = Install.Loading
        publish()
        installJob = scope.launch {
            installFlow = try {
                val fetched = withTimeoutOrNull(MANIFEST_TIMEOUT) { transport.manifest(transportUrl) }
                    ?: throw AddonException(AddonError.Timeout)
                installConsent = false
                // The link has done its job: the preview shows the manifest, not the (secret) URL.
                Install.Previewing(Preview(transportUrl, fetched.manifest, fetched.manifest.behaviorHints.configurationRequired), null)
            } catch (e: AddonException) {
                Install.EnterLink(typed, e.error.message)
            }
            publish()
        }
    }

    private fun install(pending: Preview) {
        val update = action(pending) is AddonInstallAction.Update
        installFlow = Install.Installing(pending)
        publish()
        installJob = scope.launch {
            installFlow = try {
                val descriptor = registry.install(pending.link, watchActivity = installConsent && asksConsent(pending))
                val name = isolate(descriptor.manifest.name)
                Install.Installed(if (update) T.addons.install.updated(name) else T.addons.install.installed(name))
            } catch (e: AddonException) {
                if (e.error is AddonError.ConfigurationRequired) {
                    Install.Previewing(pending.copy(configurationRequired = true), null)
                } else {
                    Install.Previewing(pending, e.error.message)
                }
            }
            publish()
            waitingLink?.let { link ->
                waitingLink = null
                previewInstall(link)
            }
        }
    }

    private fun action(pending: Preview): AddonInstallAction {
        val text = T.addons.install
        if (pending.configurationRequired) return AddonInstallAction.Configure(text.configure)
        val current = installed.firstOrNull { it.transportUrl == pending.transportUrl } ?: return AddonInstallAction.Install(text.confirm)
        return if (current.manifest == pending.manifest) AddonInstallAction.None else AddonInstallAction.Update(text.update)
    }

    /** The preview asks for the watch-activity tick: an install or update of an addon that asks for it without the user's agreement. */
    private fun asksConsent(pending: Preview): Boolean =
        pending.manifest.receivesWatchActivity && choices[pending.transportUrl]?.watchActivity != true &&
            action(pending).let { it is AddonInstallAction.Install || it is AddonInstallAction.Update }

    /** Only a new install waits for the tick; an update without it leaves the addon without watch activity. */
    private fun canConfirm(pending: Preview): Boolean =
        installConsent || !asksConsent(pending) || action(pending) !is AddonInstallAction.Install

    /** Browse's catalogs: every addon catalog of the enabled addons that needs no extra, by id; null without Browse. */
    private fun browse(): List<BrowseCatalog>? {
        if (catalogs == null) return null
        return installed.filter { choices[it.transportUrl]?.enabled != false }.flatMap { addon ->
            addon.manifest.addonCatalogs.filter { it.isExtraSupported(emptyList()) }.groupBy { it.id }.map { (id, same) ->
                BrowseCatalog(addon, id, same.first().name?.takeIf { it.isNotBlank() } ?: id, same.map { it.type })
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** The catalog and type Browse lists: the ones chosen while they exist, else the first catalog in "all" or its first type. */
    private fun browseSelection(all: List<BrowseCatalog>): Pair<BrowseCatalog, String> {
        val catalog = all.firstOrNull { it.key == chosenCatalog } ?: all.first()
        val type = chosenType?.takeIf { it in catalog.types } ?: ALL_CATALOG_TYPE.takeIf { it in catalog.types } ?: catalog.types.first()
        return catalog to type
    }

    /** Fetches what Browse lists unless it is what was fetched already ([again]: whatever was). */
    private fun loadBrowse(again: Boolean = false) {
        val aggregator = catalogs ?: return
        val (catalog, type) = browseSelection(browse() ?: return)
        val request = ResourceRequest(catalog.addon.transport, ResourcePath(Resources.ADDON_CATALOG, type, catalog.id))
        if (request == browseRequest && !again) return publish()
        browseJob?.cancel()
        browseRequest = request
        browseItems = emptyList()
        browseError = null
        browseLoading = true
        publish()
        browseJob = scope.launch {
            aggregator.load(listOf(PlannedRequest(catalog.addon, request)), ContentKind.ADDONS).collect { states ->
                when (val state = states.single().state) {
                    Loadable.Loading -> return@collect
                    is Loadable.Ready -> browseItems = state.content
                    is Loadable.Error -> browseError = state.error.message
                }
                browseLoading = false
                publish()
            }
        }
    }

    private fun reorder(moving: Descriptor, target: Descriptor, before: Boolean) = perform {
        val order = storedOrder() - moving.transportUrl
        val targetIndex = order.indexOf(target.transportUrl)
        if (targetIndex >= 0) registry.move(moving.transportUrl, if (before) targetIndex else targetIndex + 1)
    }

    /** Stores [change] once the changes before it are stored (none is dropped); its failure becomes the [notice]. */
    private fun perform(change: suspend () -> Unit) {
        pendingChanges++
        notice = null
        publish()
        scope.launch {
            try {
                storing.withLock { change() }
            } catch (e: AddonException) {
                notice = e.error.message
            } finally {
                pendingChanges--
                publish()
            }
        }
    }

    private fun closeDetailsQuietly() {
        detailsUrl = null
        linkRevealed = false
    }

    private fun find(id: String): Descriptor? = installed.firstOrNull { idOf(it) == id }

    private fun visible(): List<Descriptor> = typeFilter?.let { type -> installed.filter { type in it.manifest.types } } ?: installed

    private fun idOf(addon: Descriptor): String = ids.getOrPut(addon.transportUrl) { "addon-${ids.size + 1}" }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): AddonsUiState {
        val shown = visible()
        val types = installed.flatMap { it.manifest.types }.distinct().sortedWith(typeOrder)
        val browse = browseState()
        return AddonsUiState(
            title = T.addons.title,
            section = if (browse == null) AddonsSection.INSTALLED else section,
            typeFilters = listOf(AddonTypeFilter(ALL_TYPES, T.addons.allTypes, typeFilter == null)) +
                types.map { AddonTypeFilter(it, typeLabel(it), it == typeFilter) },
            body = when {
                !loaded -> AddonsBody.Loading
                shown.isNotEmpty() -> AddonsBody.Rows(shown.mapIndexed { index, addon -> row(addon, index, shown.lastIndex) })
                typeFilter != null -> AddonsBody.Empty(T.addons.emptyFiltered)
                else -> AddonsBody.Empty(T.addons.empty)
            },
            installLabel = T.addons.install.action,
            install = installState(),
            browse = browse,
            details = detailsUrl?.let { url -> installed.firstOrNull { it.transportUrl == url } }?.let(::detailsState),
            removeDialog = removeUrl?.let { url -> installed.firstOrNull { it.transportUrl == url } }?.let { addon ->
                AddonRemoveDialog(
                    id = idOf(addon),
                    title = T.addons.remove.title(isolate(addon.manifest.name)),
                    message = T.addons.remove.message,
                    confirmLabel = T.addons.remove.confirm,
                    cancelLabel = T.addons.cancel,
                )
            },
            working = pendingChanges > 0,
            notice = notice,
        )
    }

    private fun row(addon: Descriptor, index: Int, lastIndex: Int): AddonRow {
        val manifest = addon.manifest
        val choice = choices[addon.transportUrl]
        val name = isolate(manifest.name)
        return AddonRow(
            id = idOf(addon),
            name = manifest.name,
            description = manifest.description?.takeIf { it.isNotBlank() },
            logo = manifest.logo,
            facts = AddonText.facts(manifest),
            note = when {
                addon.flags.protected -> T.addons.builtIn
                manifest.receivesWatchActivity && choice?.watchActivity != true -> T.addons.watchActivity.notAllowed
                else -> null
            },
            configurable = AddonRegistry.configureUrl(addon) != null,
            removable = !addon.flags.protected,
            enabled = choice?.enabled != false,
            canDisable = !addon.flags.protected,
            enabledLabel = T.addons.enabledNamed(name),
            canMoveUp = index > 0,
            canMoveDown = index < lastIndex,
            moveUpLabel = T.addons.moveUpNamed(name),
            moveDownLabel = T.addons.moveDownNamed(name),
            dragLabel = T.addons.dragToReorderNamed(name),
            moreLabel = T.detail.moreActionsNamed(name),
        )
    }

    private fun detailsState(addon: Descriptor): AddonDetailsUiState {
        val manifest = addon.manifest
        val text = T.addons.details
        return AddonDetailsUiState(
            id = idOf(addon),
            name = manifest.name,
            version = AddonText.version(manifest),
            description = manifest.description?.takeIf { it.isNotBlank() },
            logo = manifest.logo,
            background = manifest.background,
            typesLabel = text.types,
            types = AddonText.types(manifest),
            resourcesLabel = text.resources,
            resources = AddonText.resources(manifest),
            catalogsLabel = text.catalogs,
            catalogs = AddonText.catalogs(manifest),
            contactLabel = text.contact,
            contact = manifest.contactEmail?.takeIf { it.isNotBlank() },
            note = if (addon.flags.protected) T.addons.builtInExplanation else null,
            configurable = AddonRegistry.configureUrl(addon) != null,
            configureLabel = text.configure,
            removable = !addon.flags.protected,
            removeLabel = text.remove,
            enabled = choices[addon.transportUrl]?.enabled != false,
            canDisable = !addon.flags.protected,
            enabledLabel = text.enabled,
            enabledNote = text.enabledOff.takeIf { choices[addon.transportUrl]?.enabled == false },
            watchActivity = if (manifest.receivesWatchActivity) {
                AddonWatchActivity(T.addons.watchActivity.title, T.addons.watchActivity.explanation, choices[addon.transportUrl]?.watchActivity == true)
            } else {
                null
            },
            share = ShareLinkUiState(
                title = text.shareTitle,
                warning = text.shareWarning,
                revealed = linkRevealed,
                link = if (linkRevealed) addon.transportUrl else null,
                revealLabel = text.revealLink,
                hideLabel = text.hideLink,
                copyLabel = text.copyLink,
            ),
        )
    }

    private fun browseState(): AddonBrowseUiState? {
        val all = browse() ?: return null
        val (selected, type) = browseSelection(all)
        val text = T.addons.browse
        return AddonBrowseUiState(
            label = text.title,
            installedLabel = text.installed,
            catalogs = all.mapIndexed { index, catalog -> AddonBrowseChoice("$index", catalog.name, catalog === selected) },
            types = selected.types.map { AddonBrowseChoice(it, if (it == ALL_CATALOG_TYPE) T.addons.allTypes else typeLabel(it), it == type) },
            body = browseError.let { error ->
                when {
                    browseLoading -> AddonBrowseBody.Loading
                    error != null -> AddonBrowseBody.Failed(error)
                    else -> AddonBrowseBody.Results(
                        browseItems.mapIndexed { index, addon ->
                            AddonBrowseItem(
                                id = "$index",
                                name = addon.manifest.name,
                                description = addon.manifest.description?.takeIf { it.isNotBlank() },
                                logo = addon.manifest.logo,
                                types = AddonText.types(addon.manifest),
                                note = if (installed.any { it.transportUrl == addon.transportUrl }) text.installedNote else null,
                            )
                        },
                    )
                }
            },
            retryLabel = T.nav.retry,
        )
    }

    private fun installState(): AddonInstallUiState {
        val text = T.addons.install
        return AddonInstallUiState(
            step = when (val now = installFlow) {
                Install.Closed -> AddonInstallStep.Closed
                is Install.EnterLink -> AddonInstallStep.EnterLink(now.link, canSubmit = now.link.isNotBlank(), error = now.error)
                Install.Loading -> AddonInstallStep.Loading
                is Install.Previewing -> AddonInstallStep.Preview(previewState(now.pending), now.error)
                is Install.Installing -> AddonInstallStep.Installing(previewState(now.pending))
                is Install.Installed -> AddonInstallStep.Installed(now.result)
            },
            title = text.title,
            linkHint = text.linkHint,
            submitLabel = text.submit,
        )
    }

    private fun previewState(pending: Preview): AddonPreviewUiState {
        val text = T.addons.install
        val manifest = pending.manifest
        val action = action(pending)
        val current = installed.firstOrNull { it.transportUrl == pending.transportUrl }
        return AddonPreviewUiState(
            name = manifest.name,
            version = AddonText.version(manifest),
            description = manifest.description?.takeIf { it.isNotBlank() },
            logo = manifest.logo,
            types = AddonText.types(manifest),
            resources = AddonText.resources(manifest),
            catalogs = AddonText.catalogs(manifest),
            status = when (action) {
                is AddonInstallAction.Configure -> text.configureFirst
                AddonInstallAction.None -> text.alreadyInstalled
                is AddonInstallAction.Update ->
                    if (current?.manifest?.version == manifest.version) text.updateDetails else text.updateVersion(isolate(manifest.version))
                is AddonInstallAction.Install -> null
            },
            warning = if (manifest.behaviorHints.adult) text.adult else null,
            watchActivity = if (asksConsent(pending)) {
                AddonWatchActivity(T.addons.watchActivity.agree, T.addons.watchActivity.explanation, installConsent)
            } else {
                null
            },
            action = action,
            canConfirm = canConfirm(pending),
            configureLabel = text.configure.takeIf {
                action !is AddonInstallAction.Configure && manifest.behaviorHints.configurable && TransportUrl.configureUrl(pending.transportUrl) != null
            },
            cancelLabel = T.addons.cancel,
        )
    }

    /** Where an install is, with what the store builds that step's [AddonInstallStep] from. */
    private sealed interface Install {
        data object Closed : Install

        data class EnterLink(val link: String, val error: String?) : Install

        data object Loading : Install

        data class Previewing(val pending: Preview, val error: String?) : Install

        data class Installing(val pending: Preview) : Install

        data class Installed(val result: String) : Install
    }

    private data class Preview(val link: TransportUrl, val manifest: Manifest, val configurationRequired: Boolean) {
        val transportUrl: String get() = link.value
    }

    /** One addon catalog of Browse: [addon]'s catalog [id] in [types]. */
    private class BrowseCatalog(val addon: Descriptor, val id: String, val name: String, val types: List<String>) {
        val key: Pair<String, String> get() = addon.transportUrl to id
    }

    companion object {
        /** The [AddonTypeFilter.id] of "All" (no content type is empty). */
        const val ALL_TYPES: String = ""

        private val MANIFEST_TIMEOUT = 30.seconds

        /** The addon catalog type of every type (Cinemeta's "all"). */
        private const val ALL_CATALOG_TYPE = "all"
    }
}
