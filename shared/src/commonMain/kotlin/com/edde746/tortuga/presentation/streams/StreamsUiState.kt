@file:OptIn(ExperimentalObjCName::class)

package com.edde746.tortuga.presentation.streams

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.streams.StreamBadge
import com.edde746.tortuga.playback.streams.StreamText
import com.edde746.tortuga.playback.streams.StreamTextRun
import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.presentation.settings.SettingChoiceOption
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The Streams screen: one group per stream addon, in the installed order, each filled as
 * its addon answers; with an addon selected in the row over the list ([addons]), that addon's groups alone.
 *
 * @param subtitle the episode line (`S1E2 · Pilot`), or null for a movie.
 * @param body what shows below the header: the spinner, the text saying there is nothing, or the groups.
 * @param notice why the list opened instead of a stream playing ("Auto-play found no stream that passes your
 *   filters"), or, in [downloadMode], that a pick downloads ("Choose the stream to download."): one line on top of the
 *   list; null when it was opened to pick.
 * @param filter the streams the profile's filters hide in this list, and the action to show or hide them; null when
 *   they hide none.
 * @param downloadMode the list is for downloading ([com.edde746.tortuga.presentation.navigation.Route.Streams.download]):
 *   picking a row queues its download and closes the list; only rows that can be downloaded are listed.
 * @param downloadMessage what came of a row's Download action ([StreamsStore.download]): "Added to Downloads", or
 *   that it failed; null until then and once dismissed ([StreamsStore.dismissDownloadMessage]).
 * @param playWith the "Play with…" submenu of every row that plays ([StreamRow.external] false): the stream once in
 *   Tortuga or a player app ([StreamsStore.playWith]); null where this device has no player app, and in
 *   [downloadMode].
 * @param addons the row over the list: All, then one chip per addon in the groups' order, the selected one's groups
 *   alone listed ([StreamsStore.selectAddon]); empty while fewer than two addons have one, when no row shows. All is
 *   selected while none of them is.
 * @param sort what the list is sorted by and the menu that changes it ([StreamsStore.toggleSort]); null where the
 *   screen offers no sort.
 */
data class StreamsUiState(
    val title: String,
    val subtitle: String?,
    val body: StreamsBody,
    val notice: String?,
    val filter: StreamFilterBar?,
    val downloadMode: Boolean = false,
    val downloadMessage: String? = null,
    val playWith: PlayWithMenu? = null,
    val addons: List<StreamsAddon> = emptyList(),
    val sort: StreamSortMenu? = null,
) {
    /** The groups listed: [body]'s [StreamsBody.Groups], else none. */
    val groups: List<StreamGroup> get() = (body as? StreamsBody.Groups)?.groups.orEmpty()

    /** Every addon has answered: no spinner shows, the list's or a group's. */
    val answered: Boolean
        get() = body !is StreamsBody.Loading && groups.none { it.status is GroupStatus.Loading }

    /** The selected addon's chip ([StreamsAddon.id]); null while All is. */
    val selectedAddon: String?
        get() = addons.firstOrNull { it.selected }?.id

    /** Every group's heading names its addon: All is selected (a selected addon's chip names the list instead). */
    val namesGroups: Boolean
        get() = selectedAddon == null

    /** The list's first stream's row id ([StreamRow.id]); null while it has none. */
    val firstStream: String?
        get() = groups.firstNotNullOfOrNull { it.streams.firstOrNull() }?.id

    /**
     * [group] shows its heading: always while groups are named ([namesGroups]), else only while its addon is asked or
     * failed, for its spinner or its error and Retry. The sorted list ([StreamGroup.sorted]) has none: it is no addon's.
     */
    fun headed(group: StreamGroup): Boolean = (namesGroups && !group.sorted) || group.status !is GroupStatus.Ready

    /** The number of the first row of the group at [groupIndex] in the list, whose rows are numbered across the groups. */
    fun firstRow(groupIndex: Int): Int = groups.subList(0, groupIndex).sumOf { it.streams.size }
}

/**
 * An addon's chip in the row over the stream list ([StreamsUiState.addons]).
 *
 * @param id what [StreamsStore.selectAddon] takes; the addon's for as long as the list is open.
 * @param selected the list shows this addon's groups alone.
 */
data class StreamsAddon(val id: String, val name: String, val selected: Boolean)

/**
 * The sort of the stream list ([StreamsUiState.sort]), the profile's for every list and for auto-play.
 *
 * @param title the menu's title ("Sort").
 * @param label what the list goes by: "Addon order", or the keys in order ("Resolution, Largest size first").
 * @param sorted some key is in effect: the list is one sorted group ([StreamGroup.sorted]).
 * @param options Addon order, then each key; the ones in effect [SettingChoiceOption.selected]. Picking one
 *   ([StreamsStore.toggleSort] with its id) adds it after the others or takes it out; Addon order clears them.
 */
data class StreamSortMenu(val title: String, val label: String, val sorted: Boolean, val options: List<SettingChoiceOption>) {
    /** The sort button's name for screen readers (and the Mac's tooltip): "Sort by: Resolution". */
    val spoken: String get() = T.library.sortByNamed(label)
}

/**
 * What the Streams screen shows below its header, the first that applies: why there is nothing to list when the
 * installed addons can't be read ([Empty]); a spinner while no addon is known yet and none is listed ([Loading]); that
 * there are no streams once every addon answered with none to show (none hidden by the filters, none noted as one this
 * app can't play) and none failed ([Empty]); else the groups, a failed addon's with its error and Retry ([Groups]).
 */
sealed interface StreamsBody {
    data object Loading : StreamsBody

    /** [text] says why there is nothing to list ("No streams found"). */
    data class Empty(val text: String) : StreamsBody

    /** [groups]: the groups listed, in order; none only while the filters hide every stream ([StreamsUiState.filter]). */
    data class Groups(val groups: List<StreamGroup>) : StreamsBody
}

/**
 * The filters' line at the top of the list: [text] ("12 streams hidden by your filters", or while they show "Showing 12
 * streams your filters hide") and a button labelled [actionLabel] ("Show" / "Hide") that calls
 * [StreamsStore.toggleFiltered].
 *
 * @param showing the hidden streams are listed now (each noted as filtered).
 */
data class StreamFilterBar(val text: String, val actionLabel: String, val showing: Boolean)

/**
 * One addon's streams, listed while it has something to show: while its addon is asked ([GroupStatus.Loading]), once
 * it failed ([GroupStatus.Failed], with Retry), or once it answered with streams, a note of the ones this app can't play or ones the
 * filters hide. An answer with none of these leaves the list.
 *
 * @param id the group's while the list is open, whichever groups before it come and go: what a UI keys it by.
 * @param unplayableNote how many of its streams this app can't play and hides ("3 streams this app can't play"), in
 *   builds whose [StreamListPolicy] shows it.
 * @param filteredNote how many of its streams the profile's filters hide ("3 streams hidden by your filters"), while
 *   they're hidden and more than one group hides some (one group's count is [StreamsUiState.filter]'s), or the filters
 *   hide every stream it lists; shown like [unplayableNote], under the group's rows.
 * @param sorted the list is sorted ([StreamsUiState.sort]): this group holds every answered addon's rows in that
 *   order, [addonName] being what it is sorted by; it shows no heading, and the addons still answering or failed
 *   follow it.
 */
data class StreamGroup(
    val id: String,
    val addonName: String,
    val status: GroupStatus,
    val streams: List<StreamRow>,
    val unplayableNote: String?,
    val filteredNote: String?,
    val sorted: Boolean = false,
) {
    /** Retry's spoken label, with the addon's name ("Retry Example Streams"), while it [GroupStatus.Failed]. */
    val retryLabel: String
        get() = T.streams.retryNamed(isolate(addonName))
}

/** Where a [StreamGroup]'s addon is: asked ([Loading]), answered ([Ready]), or failed with nothing to show ([Failed]). */
sealed interface GroupStatus {
    data object Loading : GroupStatus

    data object Ready : GroupStatus

    /** [error] says why (a timeout, an HTTP error), worded. */
    data class Failed(val error: String) : GroupStatus
}

/**
 * One stream, as the addon describes it. The list never touches its URL before it's picked.
 *
 * @param id what [StreamsStore.pick] takes.
 * @param name the addon's `name`, else the addon's own name.
 * @param external picking it opens a page or app outside the player (`externalUrl`, YouTube), not the player.
 * @param lastUsed the stream this video last played from, on any device (stream history).
 * @param filtered the profile's filters hide it: it's listed because the filter bar's Show is on.
 * @param noteKind which one-line note the row carries ([note]), the first that applies: [StreamNoteKind.LAST_USED],
 *   [StreamNoteKind.FILTERED], [StreamNoteKind.EXTERNAL]; null for none.
 * @param downloadActionText the label of the row's Download action ([StreamsStore.download]): "Download"; null where it
 *   has none (no downloads on this device, a row that opens outside the app, and in download mode, where the pick
 *   itself downloads).
 * @param suggestion the row is the stream suggested from the profile's recent picks on this kind of device, with why;
 *   null for every other row, and while suggestions are off.
 */
data class StreamRow(
    val id: String,
    val name: String,
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String?,
    val badges: List<StreamBadge>,
    val external: Boolean,
    val lastUsed: Boolean,
    val filtered: Boolean,
    val noteKind: StreamNoteKind?,
    val downloadActionText: String? = null,
    val suggestion: StreamSuggestion? = null,
) {
    /** [description] as runs of one style each, its styled Unicode written plainly ([StreamText.runs]); null without one. */
    val descriptionRuns: List<StreamTextRun>? = description?.let(StreamText::runs)

    /** The row's note ([noteKind]): "Last played", "Hidden by your filters" or "Opens outside the app". */
    val note: String?
        get() = when (noteKind) {
            StreamNoteKind.LAST_USED -> T.streams.lastUsed
            StreamNoteKind.FILTERED -> T.streams.filters.rowNote
            StreamNoteKind.EXTERNAL -> T.streams.external
            null -> null
        }

    /** The Download button's spoken label, with the row's name ("Download 1080p"); null without the action. */
    val downloadActionLabel: String?
        get() = downloadActionText?.let { T.streams.download.actionNamed(isolate(name)) }
}

/**
 * A suggested row's mark ([StreamRow.suggestion]): [label] "Suggested" and [reason], one line of why ("Like your recent
 * picks: 1080p, HEVC, English audio").
 */
data class StreamSuggestion(val label: String, val reason: String)

/** Which note a [StreamRow] carries, each with its icon. */
enum class StreamNoteKind {
    /** "Last played": the stream this video last played from ([StreamRow.lastUsed]). */
    LAST_USED,

    /** "Hidden by your filters": listed because the filter bar's Show is on ([StreamRow.filtered]). */
    FILTERED,

    /** "Opens outside the app": picking it opens a page or app outside the player ([StreamRow.external]). */
    EXTERNAL,
}

/**
 * What the stream list may show in this build.
 *
 * @param unplayableNote under each addon, how many of its streams this app can't play (streams that aren't direct
 *   links are always hidden): sideloaded builds only.
 * @param cacheBadge the "Instant" / "Not instant" badge, as the addon's text marks the stream: never on Apple builds.
 */
data class StreamListPolicy(val unplayableNote: Boolean, val cacheBadge: Boolean)

/** What the Streams screen asks of the app around it; called on the main thread. */
interface StreamsHost {
    /** Open the player for [request]: Tortuga's, or the player app Settings → Player names. */
    fun play(request: PlayRequest)

    /**
     * Play [request] once in the player [playerId] (`PlayerApp.TORTUGA_ID` or a player app's id, from
     * [StreamsUiState.playWith]), whatever Settings → Player says.
     */
    fun playWith(request: PlayRequest, playerId: String)

    /** Open [url] outside the app: the browser, or the app that handles it (YouTube). */
    fun openUrl(url: String)

    /** Follow a `stremio:` link inside the app. */
    fun openDeepLink(link: DeepLink)

    /** Close the Streams screen: a pick in download mode queued its download. */
    fun closeStreams()
}
