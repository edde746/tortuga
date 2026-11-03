@file:OptIn(ExperimentalObjCName::class)

package com.edde746.tortuga.presentation.addons

import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.strings.T
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Settings' Addons section: the profile's installed addons in the user's order, filterable
 * by content type as in Stremio, with installing, details, reordering, configuring and removing; and, where the build
 * has it, Browse: the addons the installed addons' addon catalogs list ([browse]).
 *
 * Transport URLs are secrets (API keys live in them): rows carry opaque [AddonRow.id]s, and a URL reaches the
 * state only when the user reveals an addon's link ([AddonDetailsUiState.share]).
 *
 * @param section which list shows: the installed addons, or [browse].
 * @param typeFilters "All" and each type the installed addons serve; the selected one filters [body]'s rows.
 * @param body what the installed addons' list shows.
 * @param browse Browse, or null where there is none: the App Store build, or no installed addon
 *   lists addons.
 * @param details the addon whose details are open, or null.
 * @param removeDialog the removal being confirmed, or null.
 * @param working a reorder or removal is being stored.
 * @param notice why the last change failed, until dismissed or the next change.
 */
data class AddonsUiState(
    val title: String,
    val section: AddonsSection,
    val typeFilters: List<AddonTypeFilter>,
    val body: AddonsBody,
    val installLabel: String,
    val install: AddonInstallUiState,
    val browse: AddonBrowseUiState?,
    val details: AddonDetailsUiState?,
    val removeDialog: AddonRemoveDialog?,
    val working: Boolean,
    val notice: String?,
) {
    /**
     * The installed addons' type filter's chip: "Type" while All is picked, else the type ("Movies"); null in Browse and
     * while the addons serve one type.
     */
    val typeChip: FilterChip?
        get() {
            if (section != AddonsSection.INSTALLED || typeFilters.size < 2) return null
            val all = typeFilters.first()
            val selected = typeFilters.firstOrNull { it.selected } ?: all
            return FilterChip.narrowing(T.browse.discover.type, selected.label, narrowed = selected != all)
        }
}

/**
 * What the installed addons' list shows: a spinner until the installed list has been read ([Loading]); then the
 * addons under the selected type filter ([Rows]), or why there are none ([Empty]).
 */
sealed interface AddonsBody {
    data object Loading : AddonsBody

    /** [text]: no addon is installed, or none serves the selected type. */
    data class Empty(val text: String) : AddonsBody

    /** [addons]: the installed addons the filter keeps, in the user's order, never none. */
    data class Rows(val addons: List<AddonRow>) : AddonsBody
}

/** The lists of the Addons section ([AddonsUiState.section]); `showSection` switches. */
enum class AddonsSection { INSTALLED, BROWSE }

/**
 * Browse (stremio-core's `CatalogWithFilters` of addon catalogs, Stremio's addon browser): one addon catalog of an
 * installed addon (Cinemeta's "Official" and "Community") in one type at a time. Picking an addon
 * (`previewBrowseItem`) previews its install as an install link does.
 *
 * @param installedLabel the section switch's label for the installed addons; [label] is Browse's.
 * @param catalogs the catalogs to choose from (`selectBrowseCatalog`); the selected one is listed.
 * @param types "All" and the other types the selected catalog comes in (`selectBrowseType`).
 * @param body what the selected catalog's list shows.
 * @param retryLabel the label of `retryBrowse`, which asks again after [AddonBrowseBody.Failed].
 */
data class AddonBrowseUiState(
    val label: String,
    val installedLabel: String,
    val catalogs: List<AddonBrowseChoice>,
    val types: List<AddonBrowseChoice>,
    val body: AddonBrowseBody,
    val retryLabel: String,
)

/**
 * What Browse's list shows: a spinner while the selected catalog is fetched ([Loading]); else why it couldn't be
 * fetched or that it lists nothing ([Failed]); else its addons ([Results]).
 */
sealed interface AddonBrowseBody {
    data object Loading : AddonBrowseBody

    data class Failed(val text: String) : AddonBrowseBody

    /** [items]: the selected catalog's addons, in its order. */
    data class Results(val items: List<AddonBrowseItem>) : AddonBrowseBody
}

/** A catalog or type of Browse; [id] is what `selectBrowseCatalog` or `selectBrowseType` takes. */
data class AddonBrowseChoice(val id: String, val label: String, val selected: Boolean)

/**
 * An addon a catalog lists, as the catalog describes it; [id] is what `previewBrowseItem` takes, valid while the list
 * shows.
 *
 * @param note "Installed", when the profile has this addon.
 */
data class AddonBrowseItem(
    val id: String,
    val name: String,
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String?,
    val logo: String?,
    val types: String,
    val note: String?,
)

/** One content-type filter; [id] is what `filterType` takes. */
data class AddonTypeFilter(val id: String, val label: String, val selected: Boolean)

/**
 * An installed addon. [id] is what the store's intents take; it stays the same while the store lives, whatever the
 * order.
 *
 * @param facts its version, types and what it gives the app on one line, "Version 1.2.0 · Movies, Series · Catalogs,
 *   Streams", which breaks between words or after a separator, never before one.
 * @param note "Built in", for the addon that can't be removed; for an addon that asks for the user's watch activity
 *   without their agreement, that it gets none until they allow it in its details.
 * @param configurable it has a settings page (`{base}/configure`), opened in the browser by `configure`.
 * @param enabled its switch: off, it stays in the list but is asked for nothing (`setEnabled`).
 * @param canDisable the switch can be turned off: not for the addon that can't be removed.
 * @param enabledLabel the switch's name for assistive technology, "Use Example Streams".
 * @param canMoveUp it isn't first in the list shown (the filtered one, when a type is selected).
 * @param moveUpLabel the name of its Move up button, with its name: "Move Example Streams up".
 * @param moveDownLabel the name of its Move down button: "Move Example Streams down".
 * @param dragLabel the name of its drag handle: "Reorder Example Streams".
 * @param moreLabel the name of its menu button: "More actions for Example Streams", the name isolated.
 */
data class AddonRow(
    val id: String,
    val name: String,
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String?,
    val logo: String?,
    val facts: String,
    val note: String?,
    val configurable: Boolean,
    val removable: Boolean,
    val enabled: Boolean,
    val canDisable: Boolean,
    val enabledLabel: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val moveUpLabel: String,
    val moveDownLabel: String,
    val dragLabel: String,
    val moreLabel: String,
)

/**
 * One addon's details.
 *
 * @param catalogs each catalog as "Popular (Movies)".
 * @param contact the addon author's address, if the manifest gives one.
 * @param enabled its switch, as [AddonRow.enabled], titled [enabledLabel], with [enabledNote] while it is off.
 * @param watchActivity for an addon whose manifest asks for the user's watch activity: whether they allow it
 *   (`setWatchActivity`); null for any other addon.
 */
data class AddonDetailsUiState(
    val id: String,
    val name: String,
    val version: String,
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String?,
    val logo: String?,
    val background: String?,
    val typesLabel: String,
    val types: String,
    val resourcesLabel: String,
    val resources: String,
    val catalogsLabel: String,
    val catalogs: List<String>,
    val contactLabel: String,
    val contact: String?,
    val note: String?,
    val configurable: Boolean,
    val configureLabel: String,
    val removable: Boolean,
    val removeLabel: String,
    val enabled: Boolean,
    val canDisable: Boolean,
    val enabledLabel: String,
    val enabledNote: String?,
    val watchActivity: AddonWatchActivity?,
    val share: ShareLinkUiState,
)

/**
 * The user's agreement that an addon receives their watch activity (its `player` and `library` events: what they
 * play, pause and stop, add to or take out of the library, and mark watched): a switch or tick box titled [label],
 * with [explanation] beside it, on while [checked].
 */
data class AddonWatchActivity(val label: String, val explanation: String, val checked: Boolean)

/**
 * Sharing an addon's link, its transport URL: the only place it is shown. [link] is null until `revealLink` and again
 * after `hideLink` or closing the details; `copyLink` hands it to the clipboard without showing it.
 *
 * @param warning why the link is private: a configured addon's link carries its accounts' keys.
 */
data class ShareLinkUiState(
    val title: String,
    val warning: String,
    val revealed: Boolean,
    val link: String?,
    val revealLabel: String,
    val hideLabel: String,
    val copyLabel: String,
)

/**
 * Installing an addon from a link: an `https://…/manifest.json` URL or a `stremio://…/manifest.json`
 * link, typed or opened from outside the app. The manifest is fetched and shown; nothing is installed until
 * `confirmInstall`.
 *
 * @param step where the install is, with what that step shows.
 * @param submitLabel the label of `submitInstallLink`, in [AddonInstallStep.EnterLink].
 */
data class AddonInstallUiState(
    val step: AddonInstallStep,
    val title: String,
    val linkHint: String,
    val submitLabel: String,
)

/**
 * The steps of an install: [Closed] → `startInstall` → [EnterLink] → `submitInstallLink` (or `previewInstall(link)`
 * from any step; during [Installing], once the install ends) → [Loading] → [Preview] → `confirmInstall` →
 * [Installing] → [Installed]. A link that fails goes back to [EnterLink] with its error; a failed install stays in
 * [Preview] with its error.
 */
sealed interface AddonInstallStep {
    data object Closed : AddonInstallStep

    /**
     * @param link the link as typed (the field's text); empty for a link opened from outside the app.
     * @param canSubmit [link] isn't blank.
     * @param error why the last link failed, until the link changes.
     */
    data class EnterLink(val link: String, val canSubmit: Boolean, val error: String?) : AddonInstallStep

    /** The manifest is being fetched. */
    data object Loading : AddonInstallStep

    /** [preview] waits for `confirmInstall`; [error] says why the last install failed. */
    data class Preview(val preview: AddonPreviewUiState, val error: String?) : AddonInstallStep

    data class Installing(val preview: AddonPreviewUiState) : AddonInstallStep

    /** [result]: "Example Streams is installed." */
    data class Installed(val result: String) : AddonInstallStep
}

/**
 * The fetched manifest, before it is installed.
 *
 * @param status how it relates to what is installed, or why it can't be installed yet ("Already installed.",
 *   "Installed. This updates it to version 1.3.0.", "This addon has to be configured before it can be installed.").
 * @param action what the main button does, with its label; [AddonInstallAction.None] shows no main button.
 * @param configureLabel a second button, `configurePreview`, for an addon with settings whose main button isn't
 *   Configure already: configuring first installs it configured (its page's Install link comes back here), instead
 *   of installing it plain and configuring a second copy later. Null otherwise.
 * @param warning the addon says its content is for adults.
 * @param watchActivity for a manifest that asks for the user's watch activity and an addon that hasn't their
 *   agreement yet: the tick box of that agreement (`setInstallConsent`). Installing waits for it ([canConfirm]); an
 *   update without it leaves the addon without watch activity. Null otherwise.
 * @param canConfirm the main button can be pressed: false while a new install waits for [watchActivity]'s tick.
 */
data class AddonPreviewUiState(
    val name: String,
    val version: String,
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String?,
    val logo: String?,
    val types: String,
    val resources: String,
    val catalogs: List<String>,
    val status: String?,
    val warning: String?,
    val watchActivity: AddonWatchActivity?,
    val action: AddonInstallAction,
    val canConfirm: Boolean,
    val configureLabel: String?,
    val cancelLabel: String,
)

/**
 * The main button of an [AddonPreviewUiState]: install it, update the installed copy, or open its configuration
 * page (an addon that has to be configured first; its page then offers an install link); none for an addon installed
 * as it is.
 */
sealed interface AddonInstallAction {
    data object None : AddonInstallAction

    /** A main button, titled [label]. */
    sealed interface Confirm : AddonInstallAction {
        val label: String
    }

    data class Install(override val label: String) : Confirm

    data class Update(override val label: String) : Confirm

    data class Configure(override val label: String) : Confirm
}

/** "Remove Example Streams?" */
data class AddonRemoveDialog(val id: String, val title: String, val message: String, val confirmLabel: String, val cancelLabel: String)

/** What the Addons section asks of the app around it; called on the main thread. */
interface AddonsHost {
    /** Open [url] outside the app (`AppSession.openUrl`): an addon's configuration page. */
    fun openUrl(url: String)

    /** Put [text] on the clipboard, marked sensitive: an addon's link (it may hold a key), on the user's request. */
    fun copyToClipboard(text: String)
}
