package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.presentation.probeName

/**
 * The Downloads screen: Library's Downloads section (`LibrarySection.DOWNLOADS`), the profile's downloads on this
 * device. Every text is ready to show.
 *
 * @param title "Downloads": the section's tab.
 * @param body what the list under the toolbar shows.
 * @param offlineNote the device is offline: "You're offline. Downloads continue once you're back online."; null online.
 * @param storage the downloads' space: "12.3 GB used · 40 GB free", or "12.3 GB used" where the free space can't be told.
 * @param pauseAllText the label of [DownloadsStore.pauseAll] while something is queued or downloading; null otherwise.
 * @param resumeAllText the label of [DownloadsStore.resumeAll] while something is paused or failed; null otherwise.
 * @param playWith the players of every row's "Play with…" ([DownloadAction.PLAY_WITH], a completed download's): the
 *   download once in Tortuga or a player app ([DownloadsStore.playWith]); null where this device has no player app or
 *   none can open its files (iPhone, iPad), and then no row offers it.
 * @param notice an action on the downloads that failed ("Couldn't change the download. Try again."), shown once:
 *   [DownloadsStore.dismissNotice] after it has shown.
 * @param keepSynced the row that opens the Keep synced screen ([DownloadsStore.openKeepSynced]) while the profile has
 *   rules, where the app has that screen; null otherwise.
 * @param deleteDialog the question [DownloadsStore.deleteGroup] asks before a group's downloads go; null otherwise.
 */
data class DownloadsUiState(
    val title: String,
    val body: DownloadsBody,
    val offlineNote: String?,
    val storage: String,
    val pauseAllText: String?,
    val resumeAllText: String?,
    val playWith: PlayWithMenu? = null,
    val notice: String? = null,
    val keepSynced: KeepSyncedLink? = null,
    val deleteDialog: DownloadDeleteDialog? = null,
)

/**
 * What the Downloads list shows: a spinner until the downloads have been read ([Loading]); then the downloads
 * ([Groups]), or that nothing is downloaded ([Empty]).
 */
sealed interface DownloadsBody {
    data object Loading : DownloadsBody

    data class Empty(val text: String) : DownloadsBody

    /**
     * [groups]: each title's downloads (a series' episodes by season, then its videos without an episode number) and
     * each movie, the one with the latest download first; never none.
     */
    data class Groups(val groups: List<DownloadGroup>) : DownloadsBody
}

/** The row that opens the Keep synced screen: [title] "Keep synced" and [value] "2 series". */
data class KeepSyncedLink(val title: String, val value: String)

/**
 * The question before several downloads go at once (a group's on Downloads, a season's or a whole title's in Detail):
 * "Delete 5 episodes of Breaking Bad?", "Downloading again takes time and a connection.", answered with [confirmLabel]
 * (destructive) or [cancelLabel]. The store that asks takes the answer.
 */
data class DownloadDeleteDialog(val title: String, val message: String, val confirmLabel: String, val cancelLabel: String)

/**
 * One title's downloads: a movie on its own ([movie]), or a series (any title with episodes or with more than one
 * download) under its heading: its [seasons], then its [videos].
 *
 * @param key the meta id: stable, a list key, and what [DownloadsStore.deleteGroup] takes.
 * @param title the series' or the movie's name.
 * @param poster its poster: a `file://` URL once saved on the device, else the addon's.
 * @param summary a series' "3 episodes · 4.2 GB" ("4 videos · 4.2 GB" when one has no episode number); a movie's size
 *   once known; null otherwise.
 * @param seasons a series' episodes by season, regular seasons in order and specials last; empty for a movie.
 * @param videos a series' downloads without an episode number, newest first, after its [seasons]; empty for a movie.
 * @param videosTitle the heading over [videos] while there are any: "Other videos".
 * @param movie the title's download when it is its only one and has no episode number; null for a series.
 * @param deleteText the label of [DownloadsStore.deleteGroup], which asks first: "Delete downloads".
 * @param moreLabel the name of its menu button: "More actions for The Bear", the title isolated.
 */
data class DownloadGroup(
    val key: String,
    val type: String,
    val title: String,
    val poster: String?,
    val summary: String?,
    val seasons: List<DownloadSeason>,
    val videos: List<DownloadRow>,
    val videosTitle: String,
    val movie: DownloadRow?,
    val deleteText: String,
    val moreLabel: String,
)

/** A season's downloaded episodes, in episode order: [title] is "Season 1" or "Specials". */
data class DownloadSeason(val season: Int, val title: String, val episodes: List<DownloadRow>)

/**
 * One download.
 *
 * @param id what [DownloadsStore.open] and [DownloadsStore.perform] take.
 * @param title a movie's name, or an episode's "3. Open Wide, O Earth" ("Episode 3" without a title of its own); a
 *   video without an episode number, its own title, else the series' name.
 * @param subtitle an episode's code, "S1E3"; null for a movie or a video without an episode number.
 * @param artwork the episode's thumbnail, else the poster (a `file://` URL once saved).
 * @param statusText "Queued", "Waiting for Wi-Fi", "Waiting for a connection", "Preparing…", "Downloading · 45%",
 *   "Paused", "Failed", "Downloaded", "Cancelled".
 * @param progress how far it is, 0 to 1, until it is complete, once its size is known.
 * @param bytesText what follows [statusText] on its line: "1.2 GB of 4.5 GB" until it is complete (once bytes
 *   arrived), else its size, "4.5 GB", once known; while it downloads, the speed after them, "1.2 GB of 4.5 GB · 3.4
 *   MB/s"; null when none is known.
 * @param failureText why it failed, worded for this build; null unless [status] is [DownloadStatus.FAILED].
 * @param actions what can be done with it (play, "Play with…", pause, resume, retry, pick a stream, cancel, delete, go
 *   to the title), in the order its menu lists them.
 * @param moreLabel the name of its menu button: "More actions for 3. Open Wide, O Earth", the title isolated.
 */
data class DownloadRow(
    val id: Long,
    val metaId: String,
    val videoId: String,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val status: DownloadStatus,
    val statusText: String,
    val progress: Double?,
    val bytesText: String?,
    val failureText: String?,
    val actions: List<DownloadActionItem>,
    val moreLabel: String,
)

/** What can be done with a download ([DownloadsStore.perform]). */
enum class DownloadAction {
    /** Play it from the device: a completed download. */
    PLAY,

    /**
     * "Play with…" right after [PLAY], where this device has player apps: a submenu of [DownloadsUiState.playWith]'s
     * players, whose pick goes to [DownloadsStore.playWith]; [DownloadsStore.perform] doesn't take it.
     */
    PLAY_WITH,
    PAUSE,
    RESUME,

    /** Queue a failed or cancelled download again. */
    RETRY,

    /** Open the Streams sheet in download mode: an episode no stream could be picked for. */
    PICK_STREAM,

    /** Stop it for good, its partial file deleted. */
    CANCEL,

    /** Remove it (for this profile; its files go once no profile has it). */
    DELETE,

    /** Open its title's Detail screen. */
    OPEN_DETAIL,
    ;

    /** The action's name in test ids and accessibility identifiers (`pickStream`). */
    val id: String = probeName
}

/** One of [DownloadRow.actions]: its [label] ("Pause", "Delete download"); [destructive] ones remove something. */
data class DownloadActionItem(val action: DownloadAction, val label: String, val destructive: Boolean)

/** What the Downloads screen asks of the app around it; called on the main thread. */
interface DownloadsHost {
    /** Open the player for [request]: a download, played from the device in Tortuga or the player Settings → Player names. */
    fun play(request: PlayRequest)

    /** Play [request] (a download) once in the player [playerId] ([DownloadsUiState.playWith]). */
    fun playWith(request: PlayRequest, playerId: String)

    /** Open the Detail screen of the title [id] of [type]. */
    fun openMeta(type: String, id: String)

    /** Open the Streams sheet for [videoId] of [meta] in download mode: the pick is downloaded. */
    fun openDownloadStreams(meta: MetaItem, videoId: String)

    /** Open the Keep synced screen ([com.edde746.tortuga.presentation.navigation.Route.KeepSynced]). */
    fun openKeepSynced()
}
