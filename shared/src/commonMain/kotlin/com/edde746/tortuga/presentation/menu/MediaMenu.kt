package com.edde746.tortuga.presentation.menu

import com.edde746.tortuga.addons.AddonEvents
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonEvents
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.presentation.downloads.retryable
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.TrackerCoordinator
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.WatchedMark
import com.edde746.tortuga.trackers.WatchedMarkListener
import com.edde746.tortuga.trackers.WatchlistState
import com.edde746.tortuga.trackers.firstMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What an entry of a media item's menu does ([mediaMenu]). The entries with an [onDetail] are carried out on the
 * title's Detail screen, as its own buttons do: from a card, [MediaActions.perform] opens Detail with that action
 * ([MediaMenuHost.openDetail]), on the video the card names, else the one Detail's Play plays. [destructive] ones
 * remove something (the menus draw them in red).
 */
enum class MediaAction(val onDetail: DetailVideoAction? = null, val destructive: Boolean = false) {
    /**
     * Plays the video in progress from 0 (a movie, or the episode the item resumes) the way Detail's Play plays it: its
     * download, else auto-play's pick, else (auto-play off) the stream it last played from, else its stream list, whose
     * pick then starts at 0 too. The library's position follows the new start.
     */
    PLAY_FROM_START(onDetail = DetailVideoAction.PLAY_FROM_START),
    MARK_WATCHED,
    MARK_UNWATCHED,

    /** An episode's season, every episode of it out so far (Detail's season mark). */
    MARK_SEASON_WATCHED,
    MARK_SEASON_UNWATCHED,

    /**
     * Takes the item out of Continue Watching, a series until another of its episodes comes out
     * ([com.edde746.tortuga.data.library.LibraryRepository.rewind]); its watch history stays.
     */
    REMOVE_FROM_CONTINUE_WATCHING,

    /** Opens the title's Detail screen: on a Continue Watching card, whose press resumes it instead. */
    DETAILS,

    /** Opens the stream list of the video, whatever auto-play would pick. */
    CHOOSE_STREAM(onDetail = DetailVideoAction.CHOOSE_STREAM),

    /** Opens the stream list in download mode: the pick is downloaded (also "Pick a stream" for a download without one). */
    DOWNLOAD(onDetail = DetailVideoAction.DOWNLOAD),
    PAUSE_DOWNLOAD,
    RESUME_DOWNLOAD,
    RETRY_DOWNLOAD,
    CANCEL_DOWNLOAD(destructive = true),
    DELETE_DOWNLOAD(destructive = true),

    /** Plays the title's (or the episode's) first trailer as Detail's Trailer does: a stream in the player, YouTube outside. */
    TRAILER(onDetail = DetailVideoAction.TRAILER),
    ADD_TO_LIBRARY,
    REMOVE_FROM_LIBRARY,

    /** Puts the title on the one tracker list the menu offers ([MenuWatchlist.service]). */
    ADD_TO_WATCHLIST,

    /** Takes the title off the one tracker list the menu offers. */
    REMOVE_FROM_WATCHLIST,

    /** Opens the Watchlist sheet ([WatchlistSheet]): the title on each of several trackers' lists. */
    WATCHLISTS,
    ;

    /** The entry's name in test ids and accessibility identifiers (`markWatched`). */
    val id: String = probeName
}

/** One entry of a media item's menu: its [label], and the [action] the screen's store carries out. */
data class MediaMenuEntry(val action: MediaAction, val label: String)

/** Where the video a menu acts on stands with this device's downloads: which download entries the menu offers. */
enum class MenuDownload {
    /** Not downloaded (or cancelled): Download. */
    NONE,

    /** Queued or downloading: Pause, Cancel. */
    ACTIVE,

    /** Paused: Resume, Cancel. */
    PAUSED,

    /** Failed with its stream: Retry, Delete. */
    FAILED,

    /** Failed for want of a stream (a season download's episode nothing was found for): Pick a stream, Delete. */
    NO_STREAM,

    /** Downloaded: Delete. */
    DOWNLOADED,
}

/**
 * A media item's menu, the same wherever the item shows (a poster, a Continue Watching card, a library item, an
 * episode): the entries that apply, in this order.
 *
 * - Play from the beginning for an item [inProgress], one Play resumes (`PlaybackSession.resumePoint`): a movie, or the
 *   episode a Continue Watching card or a library item resumes, or the episode itself.
 * - Mark as watched while the item isn't [watched] or is [inProgress] (finishing it); Mark as unwatched once it is
 *   [watched]. An item in progress that was watched before offers both. An episode's season mark follows
 *   ([seasonWatched], null for none).
 * - On a Continue Watching card ([continueWatching]), whose press resumes: Remove from Continue Watching, then Go to
 *   details.
 * - Choose a stream where [chooseStream]: on a title's card the stream list of the video the card would play (a movie's
 *   default video, a series' video as Detail's Play picks it, a Continue Watching card's own video); on an episode
 *   while auto-play is on (with it off, the episode's press opens the list, but for an episode that resumes, which
 *   replays the stream it last played from).
 * - The download entries where Detail offers Download for the video and the build has downloads ([download], null
 *   for none): Download; Pause or Resume, and Cancel, on the way; Retry (or Pick a stream) and Delete once failed;
 *   Delete once downloaded.
 * - Trailer where the item already knows one ([trailer]: a catalog item's trailers, an episode's); nothing is asked
 *   of the network when the menu opens.
 * - For a title, Add to library or Remove from library by [inLibrary]; an episode (null) has neither.
 * - For a title the profile's trackers keep lists of ([watchlist]): Add to or Remove from the one tracker's list, or
 *   "Tracker watchlists" with several. Its words name the service's list, so it reads apart from the library's.
 */
internal fun mediaMenu(
    inLibrary: Boolean?,
    watched: Boolean,
    inProgress: Boolean = false,
    continueWatching: Boolean = false,
    seasonWatched: Boolean? = null,
    chooseStream: Boolean = false,
    download: MenuDownload? = null,
    trailer: Boolean = false,
    watchlist: MenuWatchlist? = null,
): List<MediaMenuEntry> {
    val text = T.mediaMenu
    return buildList {
        fun add(action: MediaAction, label: String) = add(MediaMenuEntry(action, label))
        if (inProgress) add(MediaAction.PLAY_FROM_START, text.playFromStart)
        if (!watched || inProgress) add(MediaAction.MARK_WATCHED, text.markWatched)
        if (watched) add(MediaAction.MARK_UNWATCHED, text.markUnwatched)
        when (seasonWatched) {
            true -> add(MediaAction.MARK_SEASON_UNWATCHED, text.markSeasonUnwatched)
            false -> add(MediaAction.MARK_SEASON_WATCHED, text.markSeasonWatched)
            null -> Unit
        }
        if (continueWatching) {
            add(MediaAction.REMOVE_FROM_CONTINUE_WATCHING, text.removeFromContinueWatching)
            add(MediaAction.DETAILS, text.details)
        }
        if (chooseStream) add(MediaAction.CHOOSE_STREAM, text.chooseStream)
        when (download) {
            null -> Unit
            MenuDownload.NONE -> add(MediaAction.DOWNLOAD, text.download)
            MenuDownload.ACTIVE -> {
                add(MediaAction.PAUSE_DOWNLOAD, text.pauseDownload)
                add(MediaAction.CANCEL_DOWNLOAD, text.cancelDownload)
            }
            MenuDownload.PAUSED -> {
                add(MediaAction.RESUME_DOWNLOAD, text.resumeDownload)
                add(MediaAction.CANCEL_DOWNLOAD, text.cancelDownload)
            }
            MenuDownload.FAILED -> {
                add(MediaAction.RETRY_DOWNLOAD, text.retryDownload)
                add(MediaAction.DELETE_DOWNLOAD, text.deleteDownload)
            }
            MenuDownload.NO_STREAM -> {
                add(MediaAction.DOWNLOAD, text.pickStream)
                add(MediaAction.DELETE_DOWNLOAD, text.deleteDownload)
            }
            MenuDownload.DOWNLOADED -> add(MediaAction.DELETE_DOWNLOAD, text.deleteDownload)
        }
        if (trailer) add(MediaAction.TRAILER, text.trailer)
        when (inLibrary) {
            true -> add(MediaAction.REMOVE_FROM_LIBRARY, text.removeFromLibrary)
            false -> add(MediaAction.ADD_TO_LIBRARY, text.addToLibrary)
            null -> Unit
        }
        when (val service = watchlist?.service) {
            null -> if (watchlist != null) add(MediaAction.WATCHLISTS, T.watchlist.lists)
            else -> if (watchlist.listed) {
                add(MediaAction.REMOVE_FROM_WATCHLIST, T.watchlist.remove(watchlistName(service)))
            } else {
                add(MediaAction.ADD_TO_WATCHLIST, T.watchlist.add(watchlistName(service)))
            }
        }
    }
}

/** Where [download] stands for a menu (a cancelled one counts as none). */
internal fun menuDownload(download: Download?): MenuDownload = when (download?.status) {
    null, DownloadStatus.CANCELLED -> MenuDownload.NONE
    DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING -> MenuDownload.ACTIVE
    DownloadStatus.PAUSED -> MenuDownload.PAUSED
    DownloadStatus.FAILED -> if (download.retryable) MenuDownload.FAILED else MenuDownload.NO_STREAM
    DownloadStatus.COMPLETED -> MenuDownload.DOWNLOADED
}

/** The profile's downloads on this device as the cards' menus offer them: by title id, each video's state, newest first. */
internal typealias DownloadMarks = Map<String, Map<String, MenuDownload>>

/**
 * A card's download entries ([mediaMenu]'s `download`) for the title [type]/[id] from [this] (null where the device
 * has no downloads): a movie's, by its [videoId] or else the title's newest download (as Detail's Download button
 * goes); a series' episode named by [videoId] (a Continue Watching card's); null for a series card without one (the
 * episode Detail's Play would play isn't known before Detail loads the title, whose own Download offers the seasons)
 * and for any other type.
 */
internal fun DownloadMarks?.menuDownload(type: String, id: String, videoId: String?): MenuDownload? {
    if (this == null) return null
    val title = this[id]
    return when {
        type == MOVIE -> title?.get(videoId ?: id) ?: title?.values?.firstOrNull() ?: MenuDownload.NONE
        type == SERIES && videoId != null && !videoIsTitle(id, videoId) -> title?.get(videoId) ?: MenuDownload.NONE
        else -> null
    }
}

/** A card of [type] offers Choose a stream: a title Detail's Play plays (a movie, a series, a live channel); not a channel's videos. */
internal fun choosesStream(type: String): Boolean = type == MOVIE || type == SERIES || type == TV

/** Where the cards' menus lead for the entries that open Detail ([MediaAction.DETAILS], [MediaAction.onDetail]): the screens' hosts. */
interface MediaMenuHost {
    /**
     * Open the Detail screen of [id] of [type]; its header shows from [preview] (the item as the catalog sent it, null
     * where the screen has none) until the title loads.
     */
    fun openMeta(type: String, id: String, preview: MetaItemPreview?)

    /**
     * Open the Detail screen of [id] of [type] and carry out [action] there once the title is known, on [videoId], or
     * on the video Detail's Play plays when null.
     */
    fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction)
}

/**
 * Carries out a title's menu entries for the screens that list titles (Home, Discover, Search, Library, Detail's More
 * like this): Details and the entries carried out on Detail ([MediaAction.onDetail]) open it through the screen's
 * [MediaMenuHost]; the others go through the library calls Detail and Library make, each mark then going to the
 * profile's trackers as theirs do: a movie's mark is Library's, a series' and an episode's are Detail's (every episode
 * out so far, or the one episode), with the title's meta as Detail picks it ([loadMeta]); a mark that can't have the
 * meta isn't made, and the app's notice says so ([notice]). A download's entries go to this device's [downloads] as
 * Detail's do. The Watchlist entries go to the profile's [trackers] (not on a kids profile, [kids]): one list's at
 * once, the notice saying when a change didn't go through; several lists' open the sheet ([openWatchlists]). They run
 * in [scope], so a screen or session closing mid-mark cuts none short.
 */
internal class MediaActions(
    private val profileId: String,
    private val library: LibraryRepository,
    /** Where the user's marks go after the library took them (the profile's trackers), if anywhere. */
    private val trackerMarks: WatchedMarkListener?,
    /** Where the entries run: the app's, outliving the screen and the session. */
    private val scope: CoroutineScope,
    /** Tells the user what came of an entry that couldn't be carried out: one line over the app's screens. */
    private val notice: (String) -> Unit,
    /** This device's downloads; null where it has none (Apple TV). */
    private val downloads: DownloadManager? = null,
    /** Where the user's library actions go as watch-activity events to the addons that receive them, if anywhere. */
    private val addonEvents: AddonEvents? = null,
    /** The profile's trackers, whose lists the Watchlist entries change; null where there are none. */
    private val trackers: TrackerCoordinator? = null,
    /** The profile's kids rules: a kids profile has no Watchlist entries. */
    private val kids: KidsGate? = null,
    /** Opens the Watchlist sheet of a title over the app's screens (Detail's Watchlist action too). */
    val openWatchlists: (WatchlistRequest) -> Unit = {},
    /**
     * The title's meta as Detail picks it, to mark it or one of its episodes: the addons' answer, or the one saved with
     * its downloads on this device; null when there is neither.
     */
    private val loadMeta: suspend (type: String, id: String) -> MetaItem?,
) {
    /** The profile's downloads as the cards' menus offer them ([menuDownload]); null where the device has none. */
    val downloadMarks: Flow<DownloadMarks>? = downloads?.downloads(profileId)?.map { all ->
        val marks = LinkedHashMap<String, LinkedHashMap<String, MenuDownload>>()
        for (download in all) {
            if (download.status == DownloadStatus.CANCELLED) continue
            val title = marks.getOrPut(download.metaId) { LinkedHashMap() }
            if (download.videoId !in title) title[download.videoId] = menuDownload(download)
        }
        marks
    }?.distinctUntilChanged()

    /**
     * The profile's tracker lists as the cards' menus offer them ([MenuWatchlist]), again as they change; none on a kids
     * profile. Null where there are no trackers.
     */
    val watchlistMarks: Flow<WatchlistMarks>? = trackers?.let { coordinator ->
        combine(coordinator.watchlists(), kids?.active ?: flowOf(false)) { lists, kidsActive ->
            if (kidsActive) WatchlistMarks.NONE else WatchlistMarks(lists)
        }
    }

    /**
     * Carries out [action] on [title] from a screen whose way to Detail is [host]: Details opens the title, an entry
     * carried out on Detail opens it with that entry on [videoId] (else the video Detail's Play plays), and the others
     * run in [scope] ([carryOut]), what the user is told of one that couldn't be carried out going to [notice].
     */
    fun perform(title: MetaSnapshot, videoId: String?, action: MediaAction, host: MediaMenuHost) {
        if (action == MediaAction.DETAILS) return host.openMeta(title.type, title.id, preview = null)
        if (action == MediaAction.WATCHLISTS) return openWatchlists(WatchlistRequest(title.type, title.id, title.name))
        action.onDetail?.let { return host.openDetail(title.type, title.id, videoId, it) }
        scope.launch { carryOut(title, videoId, action)?.let(notice) }
    }

    /**
     * Puts [type]/[id] ([name], [meta] where the screen has it) on [service]'s list ([listed]) or takes it off, in
     * [scope]; the notice says so when it didn't go through and couldn't be queued for a retry.
     */
    fun setWatchlisted(type: String, id: String, name: String, meta: MetaItem?, service: TrackerService, listed: Boolean) {
        val coordinator = trackers ?: return
        scope.launch { watchlistWrite(coordinator, type, id, name, meta, service, listed)?.let(notice) }
    }

    /** [setWatchlisted]'s write; what the user is told when it failed. */
    private suspend fun watchlistWrite(
        coordinator: TrackerCoordinator,
        type: String,
        id: String,
        name: String,
        meta: MetaItem?,
        service: TrackerService,
        listed: Boolean,
    ): String? {
        if (coordinator.setWatchlisted(type, id, meta, service, listed).await() != WatchlistState.FAILED) return null
        val list = watchlistName(service)
        return if (listed) T.watchlist.addFailed(title = isolate(name), list = list) else T.watchlist.removeFailed(title = isolate(name), list = list)
    }

    /**
     * Carries out [action] on [title]. [videoId] is the episode a Continue Watching card resumes: its marks are that
     * episode's (Detail's episode mark, which moves the card on), where a title's are the whole title's; its download
     * is that episode's, where a movie's is the title's. Returns what the user is told when the action can't be carried
     * out: a series' or an episode's mark without the title's meta ("Couldn't mark Breaking Bad: …").
     */
    suspend fun carryOut(title: MetaSnapshot, videoId: String?, action: MediaAction): String? {
        when (action) {
            MediaAction.MARK_WATCHED -> return markWatched(title, videoId, watched = true)
            MediaAction.MARK_UNWATCHED -> return markWatched(title, videoId, watched = false)
            MediaAction.REMOVE_FROM_CONTINUE_WATCHING -> library.rewind(profileId, title.id)
            MediaAction.ADD_TO_LIBRARY -> if (library.addToLibrary(profileId, title)) addonEvents?.libraryAdded(title.type, title.id)
            MediaAction.REMOVE_FROM_LIBRARY -> if (library.removeFromLibrary(profileId, title.id)) addonEvents?.libraryRemoved(title.type, title.id)
            MediaAction.ADD_TO_WATCHLIST, MediaAction.REMOVE_FROM_WATCHLIST -> {
                val coordinator = checkNotNull(trackers) { "no trackers, no Watchlist entries" }
                val service = watchlistMarks?.first()?.menu(title.type, title.id)?.service ?: return null
                return watchlistWrite(coordinator, title.type, title.id, title.name, null, service, listed = action == MediaAction.ADD_TO_WATCHLIST)
            }
            MediaAction.PAUSE_DOWNLOAD -> download(title, videoId)?.let { checkNotNull(downloads).pause(it.id) }
            MediaAction.RESUME_DOWNLOAD -> download(title, videoId)?.let { checkNotNull(downloads).resume(it.id) }
            MediaAction.RETRY_DOWNLOAD -> download(title, videoId)?.let { checkNotNull(downloads).retry(it.id) }
            MediaAction.CANCEL_DOWNLOAD -> download(title, videoId)?.let { checkNotNull(downloads).cancel(it.id) }
            MediaAction.DELETE_DOWNLOAD -> download(title, videoId)?.let { checkNotNull(downloads).delete(profileId, it.id) }
            MediaAction.DETAILS, MediaAction.PLAY_FROM_START, MediaAction.CHOOSE_STREAM, MediaAction.DOWNLOAD, MediaAction.TRAILER,
            MediaAction.WATCHLISTS,
            -> error("$action opens a screen: perform it, not carryOut")
            MediaAction.MARK_SEASON_WATCHED, MediaAction.MARK_SEASON_UNWATCHED -> error("a title's menu has no $action")
        }
        return null
    }

    /** The download the card's entries act on, as [menuDownload] picks it. */
    private suspend fun download(title: MetaSnapshot, videoId: String?): Download? {
        val downloads = checkNotNull(downloads) { "this device has no downloads" }
        val ofTitle = downloads.downloads(profileId).first().filter { it.metaId == title.id && it.status != DownloadStatus.CANCELLED }
        return ofTitle.firstOrNull { it.videoId == (videoId ?: title.id) } ?: ofTitle.firstOrNull()?.takeIf { title.type == MOVIE }
    }

    /** Marks [title], or its episode [videoId]; returns [carryOut]'s message when the title's meta can't be had. */
    private suspend fun markWatched(title: MetaSnapshot, videoId: String?, watched: Boolean): String? {
        val episode = videoId?.takeIf { it != title.id }
        if (episode == null && title.type == MOVIE) {
            if (library.markWatched(profileId, title, watched)) addonEvents?.titleMarked(title.type, title.id, watched)
            trackerMarks?.onMarked(WatchedMark.Title(title.id, title.type, null, watched))
            return null
        }
        val meta = loadMeta(title.type, title.id)
        if (meta == null) {
            Log.w(TAG) { "${title.type}/${title.id}: no addon answered and nothing is saved, so ${episode ?: "the title"} isn't marked" }
            return T.mediaMenu.markFailed(isolate(title.name))
        }
        if (episode == null) {
            if (library.markAllWatched(profileId, meta, watched)) addonEvents?.titleMarked(meta.type, meta.id, watched)
            trackerMarks?.onMarked(WatchedMark.Title(meta.id, meta.type, meta, watched))
        } else {
            if (library.markVideoWatched(profileId, meta, episode, watched)) addonEvents?.videosMarked(meta.type, meta.id, listOf(episode), watched)
            trackerMarks?.onMarked(WatchedMark.Video(meta, episode, watched))
        }
        return null
    }

    private companion object {
        const val TAG = "MediaActions"
    }
}

/** Stremio's type of a title without episodes, whose mark needs no meta. */
private const val MOVIE = "movie"
private const val SERIES = "series"

/** Stremio's type of a live channel, whose Play plays its own id. */
private const val TV = "tv"

/**
 * The menu actions of [profileId]'s titles in the app's scope, its marks going to its trackers, with a mark's meta as
 * Detail picks it: the addons' answer, else the meta saved with the title's downloads on this device; offline the saved
 * one first, as Detail shows it. The saved meta is taken as the addon gave it: its artwork stays the addon's URLs, which
 * the library item keeps and syncs, where [DownloadManager.savedMeta] points to this device's files. What couldn't be
 * carried out becomes the profile's [AppGraph.menuNotices] entry; a Watchlist sheet opened, its [AppGraph.watchlistSheets]
 * entry.
 */
internal fun AppGraph.mediaActions(profileId: String) = MediaActions(
    profileId, data.library, trackers?.marks(profileId), scope, { text -> menuNotices.update { it + (profileId to text) } },
    downloads, addonEvents(profileId), trackers?.coordinator(profileId), kidsGate(profileId),
    { request -> watchlistSheets.update { it + (profileId to request) } },
) { type, id ->
    // One that can't be read (logged as it was read) is as none: the addons' meta, when there is one.
    suspend fun saved() = data.downloads.savedMeta(id)?.meta
    if (network.value.online) firstMeta(profileId, type, id) ?: saved() else saved() ?: firstMeta(profileId, type, id)
}
