package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AddonEvents
import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.message
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.TitleMark
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.data.library.settled
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.KeepSynced
import com.edde746.tortuga.downloads.SeasonPick
import com.edde746.tortuga.format.formatTimecode
import com.edde746.tortuga.kids.KidsBlockReason
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.kids.KidsVerdict
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.model.StremioId
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.net.WebUrl
import com.edde746.tortuga.playback.players.PlayerAppHandoff
import com.edde746.tortuga.playback.players.PlayerAppTitle
import com.edde746.tortuga.playback.session.PlayPurpose
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.session.localPlayRequest
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import com.edde746.tortuga.presentation.downloads.DownloadRows
import com.edde746.tortuga.presentation.downloads.active
import com.edde746.tortuga.presentation.downloads.retryable
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.marked
import com.edde746.tortuga.presentation.board.snapshot
import com.edde746.tortuga.presentation.board.toCard
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.menu.MediaMenuEntry
import com.edde746.tortuga.presentation.menu.WatchlistMarks
import com.edde746.tortuga.presentation.menu.WatchlistRequest
import com.edde746.tortuga.presentation.menu.watchlistLabel
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.TrackerCoordinator
import com.edde746.tortuga.trackers.UserScore
import com.edde746.tortuga.trackers.UserScoreState
import com.edde746.tortuga.trackers.WatchedMark
import com.edde746.tortuga.trackers.WatchedMarkListener
import com.edde746.tortuga.trackers.WatchlistLine
import com.edde746.tortuga.trackers.ids.AnimeEntryTitles
import com.edde746.tortuga.trackers.ids.EntryTitle
import com.edde746.tortuga.trackers.ratings.TitleRating
import com.edde746.tortuga.trackers.ratings.TitleRatings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Detail screen's store (stremio-core `MetaDetails`): the title [id] of [type] from every installed
 * meta addon, with the profile's library item for it.
 *
 * - The title shown is the first addon's answer in the installed order, as stremio-core picks it; each addon's
 *   loading or error is in [DetailUiState.sources]. While the addons are asked again ([reload]), the title stays.
 * - Seasons are in the title's order (specials last). The current episode ([DetailUiState.currentEpisode]) is the one
 *   the primary action plays (in progress, else next up), or at first the video the page was opened at ([videoId],
 *   else [shownVideoId]) where the title lists it. The selected season is the current episode's (before the library
 *   state is read, without a video opened at, the first regular season) until [selectSeason] chooses another. Where the
 *   episodes are beside the details, the UI brings the list to the current episode ([DetailUiState.shownEpisode]) as
 *   the page opens, unless it is its season's first; when [selectSeason] chooses its season again; and when it moves on
 *   other than by the page's own marks (back from the player after finishing one), the season following it, unless
 *   another season was chosen. A mark made here ([markWatched], [markEpisodeWatched], [markSeasonWatched]) moves it
 *   without moving the season or the list, until a screen next covers this one. The one-column page brings its list to
 *   an anime list entry's first episode alone ([DetailUiState.shownEpisodeFromEntry]). Off a TV a long season's list
 *   holds one range ([DetailUiState.listedRange]): the current episode's, or the one picked ([pickRange]), which stays
 *   as a mark here moves the current episode on, until another season is chosen or the list is brought to an episode.
 * - Episode marks are Stremio's per-video watched marks ([WatchedBitField]), kept in the library item and synced.
 * - An episode not out yet ([DetailEpisode.upcoming]) is out from its release on while the page stays open: its date,
 *   its download, the primary action and the season's marks change then, with nothing else changing ([followReleases]).
 * - The primary action plays the title's `behaviorHints.defaultVideoId` when it names one, and a live title's
 *   ([MetaItemPreview.isLive]) own id, whatever videos it lists (a schedule), resuming the default video where the
 *   library has a position; otherwise it resumes the video the library points at, else plays the next episode after it
 *   that isn't watched, else the first episode out that isn't watched; a movie resumes or plays. It plays as every video
 *   here plays ([playStreamed]: Play, Resume, an episode, Continue Watching's video): its download when the device has
 *   it; else, with auto-play on, auto-play's pick ([StreamAutoPicker], the profile's stream filters applied) once the
 *   addons' answers settle it, the action busy meanwhile, and with no pick the stream list with a notice saying so;
 *   with auto-play off (the profile's [AutoPlayMode]), a video the library has a position in (one that resumes) plays
 *   the stream it last played from as its addon offers it now (never the link the stream history saved), the action
 *   busy meanwhile, and otherwise its stream list opens.
 *   [chooseStream] opens the list instead.
 * - An episode ([openEpisode]) plays the same way; while its stream is being found, the episode's info line says so.
 *   Its menu's Choose a stream opens its list instead.
 * - [videoAction] is done once, at once, on the title shown as soon as it is known (the addons' or, offline, the one
 *   saved with the downloads: [openVideo]), as its press here would do it: with a [videoId], its stream list opens, as
 *   Stremio's detail route does ([DetailVideoAction.STREAMS]); it plays as above once the profile's settings and library
 *   item are known too ([DetailVideoAction.PLAY]: Continue Watching, whose download plays offline); or, for a
 *   `?autoPlay=true` link ([DetailVideoAction.AUTO_PLAY_LINK]), it plays the same way except that with auto-play off it
 *   plays the same stream as last time, else the first that passes the filters. A card's menu entry carried out here
 *   (Play from the beginning, Choose a stream, Download, Trailer: [MediaAction.onDetail]) runs once the title, its
 *   library state and the profile's settings are known, on [videoId] or else the video Play plays (a movie's first
 *   trailer for Trailer). It never runs later on its own: it is dropped once the page plays or opens a list first, a
 *   screen covers the page, or the title loads again as the network comes back (offline it couldn't load, as the page
 *   said).
 * - An episode's menu ([DetailEpisode.menu], [episodeMenuAction]): Play from the beginning plays the episode as Play
 *   would (its download, auto-play's pick, the stream it last played from, else its list) from 0; the rest are the
 *   screen's own actions (marks, Choose a stream, the download's entries, the episode's first trailer).
 * - The ratings row ([DetailHeader.ratings]) holds the addon's IMDb rating as soon as the title is known, and grows as
 *   the profile's trackers answer with theirs ([trackerRatings]); a tracker's IMDb rating replaces the addon's
 *   ([TitleRatings.merge]).
 * - "More like this" ([DetailUiState.related], [relatedTitles]) is asked once per title as soon as it is known, again
 *   once the network is back when no source answered; its cards carry the profile's library marks ([titleMarks]), open
 *   their title ([openRelated]) and carry out their menu as a catalog's cards do ([relatedMenuAction], [actions]). On a
 *   kids profile ([kids]) its cards pass the gate in order, as a Board row's do ([KidsGate.walk]): the row shows only
 *   what the profile may see, loading while it waits for the first verdicts.
 * - The user's own score ([DetailUiState.userRating], [coordinator]) is read from each tracker that rates the title once
 *   it is known, again once the network is back; [rate] sends one score, or its removal, to them all, each line showing
 *   the score at once and then what its write came to; a write waiting to retry is read again once it has gone.
 * - The title's Watchlist action ([DetailUiState.watchlist], [coordinator]) follows its place on each tracker list that
 *   takes it once it is known, not on a kids profile; [watchlist] toggles the one list ([actions], whose notice says
 *   when that didn't go through) or opens the sheet of several.
 * - Trailers ([DetailUiState.trailers], an episode's menu's first): a YouTube video or a web page opens
 *   outside the app, an HTTP stream plays in the player as a trailer ([PlayPurpose.TRAILER]: from the start, nothing
 *   recorded).
 * - Share sends the title's link ([TitleLink]) as [DetailUiState.share]; the addon's `website` is a link group. The meta
 *   addon the details come from and the ones that failed ([DetailUiState.sources]); when none has the title, the
 *   failures and why are [DetailBody.Failed.detail]. When the installed addons can't be read, the error says so.
 * - Downloads ([downloads], where the device has them): Play, Resume and an episode play its download when the device
 *   has it (a stream picked with [chooseStream] still streams). The download button ([DetailUiState.download]) and
 *   each episode's state ([DetailEpisode.download]) follow the profile's downloads of the title, as [DetailDownloads]
 *   words them; [pressDownload], [downloadAction] and an episode's menu ([episodeMenuAction]) act on them. Deleting a
 *   season's or the whole title's downloads asks first ([DetailUiState.downloadDelete]): [confirmDownloadDelete]
 *   deletes them, [dismissDownloadDelete] keeps them; one download (an episode's, a movie's) goes at once.
 * - Offline ([network]), the title saved with its downloads shows instead, even where an addon still answers (a cached
 *   answer, the dev session's offline switch), as it does online once every addon failed ([DetailUiState.offlineNote]);
 *   once the network is back, a title that failed or was the saved one loads again.
 * - Player apps ([playerApps]): while a player app that reports live plays the title, a line says so and where it is
 *   ([DetailUiState.playerApp]); after a hand-off to one that reports nothing, the "Finished watching?" prompt asks
 *   until [answerPlayerApp] or a later playback of the title.
 * - An anime list entry (`mal:…`, `anilist:…`, `kitsu:…`, `anidb:…`: the MyAnimeList and AniList rows' items) that no
 *   installed addon has details for opens in this screen's place ([DetailHost.showTitle], with that title's own
 *   preview) as the first title it is ([animeTitles]: its IMDb title, its TMDB title, its ids on the other anime lists)
 *   that an addon has, a series at the entry's first episode (or [videoId]'s) where that title lists it. With none,
 *   the error says to install an anime addon. The titles are found while the addons are asked for the entry itself,
 *   which shows at once when one has it. Finding them waits for the anime datasets (downloaded on the first open):
 *   after [ENTRY_NOTE_AFTER] the page says so ([DetailUiState.loadingNote]); when they can't be had, or take longer
 *   than [ENTRY_TITLES_WAIT], the entry is left to the addons' answers, the error then saying the lists didn't load
 *   (Retry waits for them again; their load goes on meanwhile).
 * - With a [shownVideoId] (the title such an entry opened as), that video is the current episode at first; nothing
 *   opens.
 * - Opened from a catalog item ([preview]) that has the backdrop and the logo the page is laid out by, or from an anime
 *   list entry's card (its name and poster: its title can take seconds to find), its header shows from it while the
 *   title loads ([DetailBody.Preview]), a series' episodes' column kept for them
 *   ([DetailBody.Preview.episodesPending]).
 * - While this screen is out of view ([covered]: the player over it, another destination shown), the state waits,
 *   whatever changes (the player saving its position every 15 s); it catches up when this screen shows again. A sheet
 *   over it (Streams) doesn't cover it.
 * - On a kids profile ([kids]) nothing loads until the title's verdict is known: one it blocks shows only why
 *   ([DetailBody.Blocked]) and asks nothing (no title, streams or scores), and the page loads once a parent allows
 *   it ([askParent]); the meta's age ratings, once it loads, can block it still. An allowed title offers its blocking
 *   ([DetailUiState.kidsBlockAction], [blockOnProfile]). A title a parent allowed by its id alone (opened from a link,
 *   nothing of it loaded) takes the meta's name in the profile's allowed titles once the meta loads.
 *
 * Call everything on the main thread; [release] ends it.
 */
class DetailStore internal constructor(
    private val profileId: String,
    private val type: String,
    private val id: String,
    private val videoId: String?,
    private val shownVideoId: String?,
    private val aggregator: AddonAggregator,
    private val installedAddons: suspend () -> List<Descriptor>,
    private val library: LibraryRepository,
    private val streamHistory: StreamHistoryRepository,
    /** The profile's settings: auto-play, the stream filters and the blur of unwatched episodes' stills. */
    private val settings: Flow<ProfileSettings>,
    private val autoPicker: StreamAutoPicker,
    private val host: DetailHost,
    private val clock: Clock,
    /** The app's scope, whose child [scope] is the page's: the user's writes run in it, so leaving the page cuts none short. */
    private val parentScope: CoroutineScope,
    /** The device's network. */
    private val network: StateFlow<NetworkState>,
    /** Where the user's marks go after the library took them (the profile's trackers), if anywhere. */
    private val trackerMarks: WatchedMarkListener? = null,
    /** Where the user's library actions go as watch-activity events to the addons that receive them, if anywhere. */
    private val addonEvents: AddonEvents? = null,
    /** The profile's connected trackers' scores for a title (a growing list), if it has trackers at all. */
    private val trackerRatings: ((MetaItem) -> Flow<List<TitleRating>>)? = null,
    /** The profile's trackers, for the user's own score and the title's watchlists; null where they aren't installed. */
    private val coordinator: TrackerCoordinator? = null,
    /** This device's downloads; null where it has none (Apple TV). */
    private val downloads: DownloadManager? = null,
    /** The hand-offs to player apps; null where the platform has none. */
    private val playerApps: PlayerAppHandoff? = null,
    /** The titles an anime list entry is; null where the trackers aren't installed. */
    private val animeTitles: AnimeEntryTitles? = null,
    /** The catalog item the title was opened from, if any. */
    preview: MetaItemPreview? = null,
    /** This screen is out of view (a screen over it, another destination shown), as the stacks change. */
    private val covered: Flow<Boolean> = flowOf(false),
    /** What is done once the title is known: with [videoId] or, for a card's menu entry, the video Play plays ([openVideo]). */
    private val videoAction: DetailVideoAction = DetailVideoAction.STREAMS,
    /** The device offers Share ([DetailUiState.share]): not on TVs, which have no share sheet and no clipboard anyone reads. */
    private val sharing: Boolean = true,
    /** The sources of "More like this"; null where the page has none. */
    private val relatedTitles: RelatedTitles? = null,
    /** The profile's titles in the library or watched, by id: the related titles' marks. */
    private val titleMarks: Flow<Map<String, TitleMark>> = flowOf(emptyMap()),
    /** What a related title's menu entries do ([relatedMenuAction]); null where they do nothing. */
    private val actions: MediaActions? = null,
    /** The profile's kids rules; without them the page stays as it is. */
    private val kids: KidsGate? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var addons: List<Descriptor> = emptyList()
    private var metaStates: List<AddonLoadable<MetaItem>> = emptyList()
    private var addonsKnown = false
    private var addonsUnreadable = false
    private var item: LibraryItem? = null
    private var itemKnown = false

    /** The season chosen ([selectSeason]), while it isn't [currentEpisode]'s. */
    private var chosenSeason: Int? = null

    /**
     * The season a mark made here kept shown as [currentEpisode] moved out of it ([followCurrent]); unlike a chosen one,
     * it gives way once the current episode moves on after a screen covered this one.
     */
    private var keptSeason: Int? = null

    /**
     * [videoAction] is done or dropped: it runs at most once, at once or never. A video or list the page opens (the
     * action's, or one the user asked for first), a screen covering the page and the title loading again as the network
     * comes back each end it.
     */
    private var videoActionDone = false
    private var profile: ProfileSettings? = null

    /** Finding the stream of [pickVideoId] ([pickAndPlay]); opening a stream list or another pick cancels it. */
    private var pickJob: Job? = null
    private var pickVideoId: String? = null
    private val picking: Boolean get() = pickJob?.isActive == true

    internal fun picking(videoId: String): Boolean = picking && pickVideoId == videoId

    /**
     * Looking for a download of the video to play ([playLocalOr]); another press waits for it, and whatever cancels a
     * pick ([cancelPick]: another intent, a screen covering this one) cancels it.
     */
    private var lookupJob: Job? = null
    private val lookingUp: Boolean get() = lookupJob?.isActive == true

    /**
     * The stream addons' answers for the video the primary action plays, asked as the page shows it (when the page
     * opens, for that one video alone), for [DetailPrimaryAction.noStreams]. The next press that plays the
     * video from a stream reads them in place of its own request ([takeAnswers]); a press after that asks again, as a
     * link can be good only for the answer that gave it. A stream list opened for the video asks on its own and spends
     * them ([openList]): an addon's links can be good only until its next answer (the test addon's stale link).
     */
    private var asked: AskedStreams? = null

    private class AskedStreams(val videoId: String, val job: Job) {
        /** The answers so far; null before the first. */
        val states = MutableStateFlow<List<AddonLoadable<List<Stream>>>?>(null)

        /** Every addon has answered or failed, and none offered a stream. */
        var none = false

        /**
         * A press read them ([takeAnswers]), its request now and never cancelled when Play moves on, or a stream list
         * for the video opened ([spendAnswers]); no press reads them again.
         */
        var taken = false
    }

    /** Opens [videoId]'s stream list, which asks the addons itself ([spendAnswers]). */
    private fun openList(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean = false) {
        videoActionDone = true
        spendAnswers(videoId)
        host.openStreams(meta, videoId, notice, fromStart)
    }

    /**
     * No press reads [asked]'s answers for [videoId] once a stream list asks for it too, and the ask stops reading them;
     * a request of it still on its way runs on in the addon transport, and the list gets that answer rather than asking
     * the addon again, so the list's answer stays the addon's newest.
     */
    private fun spendAnswers(videoId: String) {
        val asking = asked?.takeIf { it.videoId == videoId && !it.taken } ?: return
        asking.taken = true
        asking.job.cancel()
    }

    private var online = network.value.online

    /** The title saved with its downloads, once read; null when there is none. */
    private var savedMeta: MetaItem? = null

    /** [meta] is [savedMeta], shown in place of the addons' answers. */
    private var showingSaved = false

    /** The profile's downloads of the title, newest first, cancelled ones left out. */
    internal var titleDownloads: List<Download> = emptyList()
    private var syncRule: KeepSynced? = null
    private var downloadNotice: String? = null

    /** The downloads [downloadAction] asks before deleting ([DetailUiState.downloadDelete]); null while nothing is asked. */
    private var deleting: DeleteAsked? = null

    /** A season's downloads ([season]), or the whole title's (null). */
    private class DeleteAsked(val season: Int?)

    /** The download button's press ([DetailDownloads.Title.press]). */
    private var downloadPress: String? = null

    /** The title shown. */
    private var meta: MetaItem? = null

    private var marksMeta: MetaItem? = null
    private var marksSource: String? = null
    private var marks: WatchedBitField? = null

    /** The title's playback in a player app: a live one, or the prompt; null for none. */
    private var playerAppTitle: PlayerAppTitle? = null

    /** The trackers' scores for [ratingsMeta], the title shown when they were asked. */
    internal var trackerScores: List<TitleRating> = emptyList()
    private var ratingsMeta: MetaItem? = null
    private var ratingsJob: Job? = null

    /** The user's own score on each tracker that rates the title; null until read. */
    private var userScores: List<UserScore>? = null

    /** The title [userScores] were read for: read again once the device is back online if a tracker couldn't be read. */
    private var scoresFor: String? = null
    private var scoresJob: Job? = null
    private var rateJob: Job? = null

    /** Each tracker's line of the title's Watchlist action; null until known. */
    private var watchlistLines: List<WatchlistLine>? = null

    /** The title [watchlistLines] follow. */
    private var watchlistFor: String? = null
    private var watchlistJob: Job? = null

    /** "More like this" for [relatedFor] (the id of the title it was asked for), its titles as cards; null until asked. */
    private var related: RelatedTitles.Result? = null
    private var relatedCards: List<MetaCard> = emptyList()
    private var relatedFor: String? = null
    private var relatedJob: Job? = null
    private var libraryMarks: Map<String, TitleMark> = emptyMap()
    private var relatedWatchlists: WatchlistMarks? = null

    /** The walk over [relatedCards] on a kids profile ([KidsGate.Paging]); the row doesn't page. */
    private val relatedPaging = KidsGate.Paging<MetaCard>(kids)

    /** [id] as an anime list entry, and its titles being looked for ([openEntryTitle]). */
    private val entry = StremioId.parse(id) as? StremioId.Anime
    private var findingTitle = false

    /** [findingTitle] for longer than [ENTRY_NOTE_AFTER]: the page says what it waits for. */
    private var findingLong = false

    /** The entry's titles couldn't be found: the anime datasets can't be had, or took longer than [ENTRY_TITLES_WAIT]. */
    private var titlesUnavailable = false

    /** [DetailUiState.currentEpisode]: null until the page is opened at a video the title lists or the library state is read. */
    private var currentEpisode: String? = null

    /** The primary action's video in the state built last, to see it move on. */
    private var seenPrimary: String? = null

    /** The season and the list follow [currentEpisode] as it moves on: not after a mark made here, until the page is covered. */
    private var followsCurrent = true

    /** [DetailUiState.shownEpisode] until the UI has shown it or another season is chosen. */
    private var episodeToShow: String? = null

    /** [episodeToShow] is [shownVideoId] as the page opens ([DetailUiState.shownEpisodeFromEntry]). */
    private var entryToShow = false

    /**
     * [DetailUiState.listedRange] as listed last, with the season it is of: picked ([pickRange]), else the range holding
     * [currentEpisode], kept from then on; null once another season is chosen or the list is brought to an episode.
     */
    private var listed: ListedRange? = null

    /** Range [index] of [season]'s ranges ([listed]). */
    private class ListedRange(val season: Int?, val index: Int)

    /**
     * [preview] where its header can stand for the title's while it loads: with the backdrop and logo the page is laid
     * out by, or an anime list entry's card as it is.
     */
    private val previewItem = preview?.takeIf { it.id == id && (entry != null || it.background != null && it.logo != null) }

    /** The catalog item the title was opened from: what the kids gate judges until the meta loads. */
    private val catalogItem = preview

    /** The kids gate's verdict on the title; null while it isn't known, [KidsVerdict.Allowed] without a gate. */
    private var kidsVerdict: KidsVerdict? = if (kids == null) KidsVerdict.Allowed else null

    /** The meta the title's entry in the profile's allowed titles was last named from ([nameAllowed]). */
    private var namedFrom: MetaItem? = null

    /** A screen covers this one: [publish] leaves the state [stale] until it shows again. */
    private var hidden = false
    private var stale = false

    /** The last state built: the next one keeps its unchanged parts, the same instances (the UIs skip them by identity). */
    private var built: DetailUiState? = null

    /** [seasonVideos] of [seasonsMeta]: its videos by season (specials included), seasons and videos in its order. */
    private var seasonsMeta: MetaItem? = null
    private var seasonVideos: Map<Int, List<Video>> = emptyMap()

    /** The library item as the last build read it ([settledItem]). */
    private var lastSettled: Settled? = null

    /** [item], read through the library's rule ([settledItem]) from [from] for [meta] with [released] of its releases out. */
    private class Settled(val from: LibraryItem, val meta: MetaItem, val released: Int, val item: LibraryItem)

    /** The marks' sums the last build read ([watchedSums]). */
    private var lastSums: WatchedSums? = null

    /**
     * What [meta]'s videos come to under [marks] with [released] of its releases out: the whole title [watched], each
     * season's mark ([seasonsWatched]), and how many of its episodes are out ([outEpisodes]).
     */
    private class WatchedSums(
        val meta: MetaItem,
        val marks: WatchedBitField,
        val released: Int,
        val watched: Boolean,
        val seasonsWatched: Map<Int, Boolean>,
        val outEpisodes: Int,
    )

    /**
     * The last build's episode rows with what they were built from ([episodeRows]): a row is built again only when that
     * changed, and while what the rows share is the same ([SharedInputs]) only the rows of the videos the library item,
     * Play, a stream being found and the downloads point at are looked at again, so a long season (an anime's thousand
     * episodes in one) costs as much as those rows as the page updates.
     */
    private var builtRows: EpisodeRows? = null

    private class EpisodeRows(
        val shared: SharedInputs,
        val downloads: List<Download>,
        /** [downloads] by video id, the first of each. */
        val downloadOf: Map<String, Download>,
        /** The videos the library item, Play and a stream being found point at, whose rows depend on them. */
        val pointed: Set<String>,
        /** Each video's position in [SharedInputs.videos]. */
        val positions: Map<String, Int>,
        val inputs: List<EpisodeInputs>,
        val rows: List<DetailEpisode>,
    )

    /** What every row of a list is built from alike, besides its video's own marks, release and download ([EpisodeInputs]). */
    private data class SharedInputs(
        val videos: List<Video>,
        val locale: String,
        val runtime: String?,
        val marks: WatchedBitField,
        val released: Int,
        val offersDownloads: Boolean,
        val chooseStream: Boolean,
        val seasonWatched: Boolean?,
        val blurUnwatched: Boolean,
    )

    /** [lastHeader] built from [headerSource] with [headerScores] and [headerSourceFacts], its links [headerLinked] or not. */
    private var headerSource: MetaItemPreview? = null
    private var headerScores: List<TitleRating>? = null
    private var headerLinked = false
    private var headerSourceFacts: List<DetailFact> = emptyList()
    private var lastHeader: DetailHeader? = null

    /** The title whose next release [releaseJob] waits for ([followReleases]). */
    private var releasesOf: MetaItem? = null
    private var releaseJob: Job? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<DetailUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        scope.launch {
            library.observeItem(profileId, id).distinctUntilChanged().collect {
                item = it
                itemKnown = true
                publish()
            }
        }
        scope.launch {
            settings.collect {
                profile = it
                publish()
            }
        }
        scope.launch {
            network.collect { state ->
                val back = state.online && !online
                online = state.online
                if (back && userScores.orEmpty().any { it.state == UserScoreState.UNREADABLE }) scoresFor = null
                if (back && related == RelatedTitles.Result.None) relatedFor = null
                // The addons' title replaces the saved one or the error: an action still waiting is dropped, not done
                // now, when the user may be elsewhere (the player).
                if (back && (showingSaved || mutableState.value.body is DetailBody.Failed)) load(dropAction = true)
                publish()
            }
        }
        playerApps?.let { handoff ->
            scope.launch {
                handoff.title(id).collect {
                    playerAppTitle = it
                    publish()
                }
            }
        }
        if (relatedTitles != null) {
            scope.launch {
                titleMarks.distinctUntilChanged().collect {
                    libraryMarks = it
                    publish()
                }
            }
            actions?.watchlistMarks?.let { flow ->
                scope.launch {
                    flow.collect {
                        relatedWatchlists = it
                        publish()
                    }
                }
            }
        }
        scope.launch {
            covered.collect {
                hidden = it
                if (it) {
                    followsCurrent = true
                    // The user is elsewhere: an action not done yet, or a stream still being found, never runs later.
                    videoActionDone = true
                    cancelPick()
                }
                if (!it && stale) publish()
            }
        }
        downloads?.let(::followDownloads)
        if (kids == null) load() else judgeFirst(kids)
    }

    /**
     * Shows the episodes of [season] (0 for specials): another season from its first episode; [currentEpisode]'s with
     * the list brought to it. On a TV focus moving onto another season's chip calls it ([DetailUiState.seasons]).
     */
    fun selectSeason(season: Int) {
        val current = currentEpisode
        val meta = meta
        keptSeason = null
        listed = null
        if (current != null && meta != null && meta.seasonOf(current) == season) {
            chosenSeason = null
            episodeToShow = current
        } else {
            chosenSeason = season
            episodeToShow = null
        }
        entryToShow = false
        publish()
    }

    /**
     * Off a TV, shows range [index] of [DetailUiState.episodeRanges] in the list under the chips: the chip of a range
     * tapped or clicked ([DetailUiState.listedRange]).
     */
    fun pickRange(index: Int) {
        val state = mutableState.value
        if (index !in state.episodeRanges.indices) return
        listed = ListedRange(state.selectedSeason, index)
        publish()
    }

    /** The UI has brought the list to [DetailUiState.shownEpisode]; it isn't asked to again. */
    fun episodeShown() {
        if (episodeToShow == null) return
        episodeToShow = null
        entryToShow = false
        publish()
    }

    /** Acts on [DetailUiState.primaryAction]: its download when the device has it, else its stream. */
    fun play() {
        val meta = meta ?: return
        val action = mutableState.value.primaryAction ?: return
        if (picking(action.videoId) || lookingUp) return
        cancelPick()
        playLocalOr(meta, action.videoId) { playStreamed(meta, action.videoId) }
    }

    /**
     * Plays [videoId] of [meta] from a stream, the one rule for Play, Resume, an episode and Continue Watching's video:
     * with auto-play on, auto-play's pick; with it off, the stream [videoId] last played from when it resumes (the
     * library has a position in it, as the primary action's Resume says) and its addon still offers it ([playRemembered]);
     * else its stream list.
     */
    private fun playStreamed(meta: MetaItem, videoId: String) {
        val profile = profile
        if (profile != null && profile.streamAutoPlay != AutoPlayMode.OFF) return autoPlay(meta, videoId, profile, fromStart = false)
        if (meta.preview.isLive || PlaybackSession.resumePoint(item, videoId) <= 0) return openList(meta, videoId, notice = null)
        playRemembered(meta, videoId, profile, fromStart = false)
    }

    /**
     * Plays [videoId] from 0 (Play from the beginning): from its download when the device has it; else with auto-play
     * on from auto-play's pick, and with it off from the stream it last played from, else from its stream list.
     */
    private fun playFromStart(videoId: String) {
        val meta = meta ?: return
        if (picking(videoId) || lookingUp) return
        cancelPick()
        playLocalOr(meta, videoId, fromStart = true) {
            val profile = profile
            if (profile == null || profile.streamAutoPlay == AutoPlayMode.OFF) playRemembered(meta, videoId, profile, fromStart = true) else autoPlay(meta, videoId, profile, fromStart = true)
        }
    }

    /**
     * Plays auto-play's pick for [videoId] once the addons' answers settle it; with none, opens its list with a notice.
     * [fromStart]: from 0.
     */
    private fun autoPlay(meta: MetaItem, videoId: String, profile: ProfileSettings, fromStart: Boolean) =
        pickAndPlay(meta, videoId, fromStart, T.streams.autoPlay.noMatch) {
            autoPicker.pick(profileId, meta, videoId, addons, profile.streamAutoPlay, StreamFilter.of(profile), StreamSort.of(profile), takeAnswers(videoId))
        }

    /**
     * Auto-play off: [videoId] replays the stream it last played from, as its addon offers it now
     * ([StreamAutoPicker.lastPlayed]: never the link the stream history saved, which another device or an earlier
     * network may have fetched), from 0 with [fromStart]; else its stream list opens.
     */
    private fun playRemembered(meta: MetaItem, videoId: String, profile: ProfileSettings?, fromStart: Boolean) =
        pickAndPlay(meta, videoId, fromStart, notice = null) {
            autoPicker.lastPlayed(profileId, meta, videoId, addons, profile?.let(StreamFilter::of) ?: StreamFilter.NONE, takeAnswers(videoId))
        }

    /**
     * Plays what [pick] finds for [videoId], the action busy meanwhile; with nothing, opens its list with [notice].
     * [fromStart]: from 0.
     */
    private fun pickAndPlay(meta: MetaItem, videoId: String, fromStart: Boolean, notice: String?, pick: suspend () -> PlayRequest?) {
        pickVideoId = videoId
        pickJob = scope.launch {
            val request = try {
                pick()
            } finally {
                if (pickJob === coroutineContext[Job]) pickJob = null
                publish()
            }
            when {
                request == null -> openList(meta, videoId, notice, fromStart)
                fromStart -> host.play(request.copy(startPositionSeconds = 0.0))
                else -> host.play(request)
            }
        }
        publish()
    }

    /** Opens the stream list for [DetailUiState.primaryAction]'s video, whatever auto-play would pick. */
    fun chooseStream() {
        val action = mutableState.value.primaryAction ?: return
        chooseEpisodeStream(action.videoId)
    }

    /** Opens the stream list for [videoId], whatever auto-play would pick (the episode menu's Choose a stream). */
    private fun chooseEpisodeStream(videoId: String) {
        val meta = meta ?: return
        cancelPick()
        openList(meta, videoId, notice = null)
    }

    /** Plays [videoId], an episode of the title, as [play] plays a video: its download when the device has it, else [playStreamed]. */
    fun openEpisode(videoId: String) {
        val meta = meta ?: return
        if (picking(videoId) || lookingUp) return
        cancelPick()
        playLocalOr(meta, videoId) { playStreamed(meta, videoId) }
    }

    /** Plays [videoId] of [meta] from its download when the device has one (from 0 with [fromStart]); else runs [otherwise]. */
    private fun playLocalOr(meta: MetaItem, videoId: String, fromStart: Boolean = false, otherwise: () -> Unit) {
        videoActionDone = true
        val downloads = downloads ?: return otherwise()
        lookupJob = scope.launch {
            val local = downloads.localVideo(profileId, meta.id, videoId)
            lookupJob = null
            if (local == null) return@launch otherwise()
            val request = localPlayRequest(meta, videoId, local)
            host.play(if (fromStart) request.copy(startPositionSeconds = 0.0) else request)
        }
    }

    /** Drops the playback being started: the download looked for ([lookupJob]), the stream being found ([pickJob]). */
    private fun cancelPick() {
        lookupJob?.cancel()
        lookupJob = null
        val job = pickJob ?: return
        pickJob = null
        job.cancel()
        publish()
    }

    // ---- Downloads ----------------------------------------------------------------------------------------------------

    /** Presses the download button ([DetailUiState.download]) when it acts at once; one that opens its menu does nothing. */
    fun pressDownload() {
        downloadPress?.let(::downloadAction)
    }

    /** Runs the entry [id] of the download button's menu ([DetailDownload.menu]). */
    fun downloadAction(id: String) {
        val meta = meta ?: return
        val downloads = downloads ?: return
        // A movie's menu is its one video's.
        if (!DetailDownloads.isSeries(meta)) return episodeDownloadAction(DetailDownloads.movieVideoId(meta), id)
        val season = mutableState.value.selectedSeason
        when {
            id.startsWith(DetailDownloads.SEASON_PREFIX) -> pick(id.removePrefix(DetailDownloads.SEASON_PREFIX))?.let { queueEpisodes(meta, season, it) }
            id.startsWith(DetailDownloads.SERIES_PREFIX) -> pick(id.removePrefix(DetailDownloads.SERIES_PREFIX))?.let { queueEpisodes(meta, null, it) }
            id.startsWith(DetailDownloads.SYNC_PREFIX) -> keepSynced(meta, id.removePrefix(DetailDownloads.SYNC_PREFIX))
            id == DetailDownloads.SYNC_RULES -> host.openKeepSynced()
            id == DetailDownloads.PAUSE_ALL -> titleDownloads.filter { it.status.active }.forEach { downloads.pause(it.id) }
            id == DetailDownloads.RESUME_ALL -> titleDownloads.forEach {
                if (it.status == DownloadStatus.PAUSED) downloads.resume(it.id) else if (it.retryable) downloads.retry(it.id)
            }
            id == DetailDownloads.DELETE_SEASON -> season?.let { askDelete(it) }
            id == DetailDownloads.DELETE_ALL -> askDelete(null)
        }
    }

    /** Runs the entry [id] of [videoId]'s download menu (an episode's menu, a movie's [downloadAction]). */
    private fun episodeDownloadAction(videoId: String, id: String) {
        val meta = meta ?: return
        val downloads = downloads ?: return
        val download = DetailDownloads.of(titleDownloads, videoId) ?: titleDownloads.firstOrNull().takeIf { !DetailDownloads.isSeries(meta) }
        when (id) {
            DetailDownloads.DOWNLOAD, DetailDownloads.PICK_STREAM -> {
                videoActionDone = true
                cancelPick()
                spendAnswers(videoId)
                host.openDownloadStreams(meta, videoId)
            }
            DetailDownloads.PAUSE -> download?.let { downloads.pause(it.id) }
            DetailDownloads.RESUME -> download?.let { downloads.resume(it.id) }
            DetailDownloads.RETRY -> download?.let { downloads.retry(it.id) }
            DetailDownloads.CANCEL -> download?.let { downloads.cancel(it.id) }
            DetailDownloads.DELETE -> download?.let { downloads.delete(profileId, it.id) }
        }
    }

    private fun askDelete(season: Int?) {
        deleting = DeleteAsked(season)
        publish()
    }

    /** Deletes the downloads [DetailUiState.downloadDelete] asked about. */
    fun confirmDownloadDelete() {
        val asked = deleting ?: return
        val meta = meta ?: return
        deleting = null
        downloads?.deleteMeta(profileId, meta.id, asked.season)
        publish()
    }

    /** [DetailUiState.downloadDelete] was dismissed: the downloads stay. */
    fun dismissDownloadDelete() {
        deleting = null
        publish()
    }

    /** [DetailUiState.downloadNotice] was shown. */
    fun dismissDownloadNotice() {
        downloadNotice = null
        publish()
    }

    private fun pick(name: String): SeasonPick? = SeasonPick.entries.firstOrNull { it.name == name }

    /** "Download season…": queues [pick] of [season]'s episodes (every season's for null), and says how many. */
    private fun queueEpisodes(meta: MetaItem, season: Int?, pick: SeasonPick) {
        val downloads = downloads ?: return
        parentScope.launch {
            downloadNotice = try {
                val queued = downloads.enqueueEpisodes(profileId, meta, season, pick, reference(meta))
                if (queued > 0) T.detail.download.queuedEpisodes(queued) else T.detail.download.nothingQueued
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "queueing episodes failed" }
                T.detail.download.failedToQueue
            }
            // The page may be gone: its notice with it.
            if (scope.isActive) publish()
        }
    }

    /** Keep synced: [value] is a count or "all". */
    private fun keepSynced(meta: MetaItem, value: String) {
        val downloads = downloads ?: return
        val rule = when (value) {
            DetailDownloads.SYNC_ALL -> KeepSynced(episodes = null)
            else -> KeepSynced(value.toIntOrNull()?.takeIf { it in KeepSynced.CHOICES } ?: return)
        }
        parentScope.launch {
            try {
                downloads.keepSynced(profileId, meta, rule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "keep synced failed" }
                downloadNotice = T.detail.download.failedToQueue
                if (scope.isActive) publish()
            }
        }
    }

    /**
     * The stream a season download picks alike (its addon and binge group): the one the episode the library points at
     * last played from, when the stream history has it.
     */
    private suspend fun reference(meta: MetaItem): StreamChoice? {
        val videoId = item?.state?.videoId ?: return null
        val entry = streamHistory.lastStream(profileId, meta.id, videoId) ?: return null
        val addonName = addons.firstOrNull { it.transportUrl == entry.transportUrl }?.manifest?.name.orEmpty()
        return StreamChoice.fromHistory(entry, addonName)?.takeIf { it.playable }
    }

    /**
     * Follows the profile's downloads of the title and its Keep synced rule, and reads the title saved with them (a read
     * that fails is tried again with the next change to the downloads).
     */
    private fun followDownloads(downloads: DownloadManager) {
        scope.launch {
            downloads.downloads(profileId).map { all -> all.filter { it.metaId == id && it.status != DownloadStatus.CANCELLED } }.distinctUntilChanged().collect {
                titleDownloads = it
                if (savedMeta == null && titleDownloads.isNotEmpty()) {
                    try {
                        savedMeta = downloads.savedMeta(type, id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, e) { "reading the title saved with its downloads failed (${e::class.simpleName})" }
                    }
                }
                publish()
            }
        }
        scope.launch {
            downloads.keepSyncedRules(profileId).collect { rules ->
                syncRule = rules.firstOrNull { it.metaId == id }?.rule
                publish()
            }
        }
    }

    /** Follows a [DetailLink]: `stremio:` links inside the app, web pages (the title's website among them) outside. */
    fun openLink(id: String) {
        val url = if (id == WEBSITE_LINK) meta?.preview?.website else id.removePrefix(LINK_PREFIX).toIntOrNull()?.let { meta?.preview?.links?.getOrNull(it) }?.url
        if (url == null) return
        val link = DeepLink.parse(url)
        when {
            link != null -> host.openDeepLink(link)
            WebUrl.isWeb(url) -> host.openUrl(url)
        }
    }

    /** Opens a [DetailTrailer] of the title: outside the app (YouTube, a web page), or in the player (an HTTP stream). */
    fun openTrailer(id: String) {
        val meta = meta ?: return
        val stream = id.removePrefix(TRAILER_PREFIX).toIntOrNull()?.let { meta.preview.trailerStreams.getOrNull(it) } ?: return
        playTrailer(meta, meta.id, stream)
    }

    /** Opens the [DetailTrailer] [trailerId] of the episode [videoId] (its menu's Trailer), as [openTrailer] does. */
    private fun openEpisodeTrailer(videoId: String, trailerId: String) {
        val meta = meta ?: return
        val video = meta.video(videoId) ?: return
        val stream = trailerId.removePrefix(TRAILER_PREFIX).toIntOrNull()?.let { video.trailerStreams.getOrNull(it) } ?: return
        playTrailer(meta, video.id, stream)
    }

    private fun playTrailer(meta: MetaItem, videoId: String, stream: Stream) {
        videoActionDone = true
        when (val playability = stream.playability) {
            is StreamPlayability.Playable -> {
                cancelPick()
                val addon = meta.addon
                host.play(PlayRequest(meta, videoId, StreamChoice(stream, addon?.transportUrl.orEmpty(), addon?.manifest?.name.orEmpty()), purpose = PlayPurpose.TRAILER))
            }
            is StreamPlayability.YouTube -> host.openUrl(playability.watchUrl)
            is StreamPlayability.External -> playability.deepLink?.let(host::openDeepLink) ?: host.openUrl(playability.url)
            is StreamPlayability.Unsupported -> {}
        }
    }

    fun addToLibrary() {
        val meta = meta ?: return
        parentScope.launch {
            if (library.addToLibrary(profileId, meta.librarySnapshot())) addonEvents?.libraryAdded(meta.type, meta.id)
        }
    }

    /** Takes the title out of the library; its watch state stays. */
    fun removeFromLibrary() {
        parentScope.launch {
            if (library.removeFromLibrary(profileId, id)) addonEvents?.libraryRemoved(meta?.type ?: type, id)
        }
    }

    /** Marks the movie, or every episode out so far, watched or unwatched. */
    fun markWatched(watched: Boolean) {
        val meta = meta ?: return
        followsCurrent = false
        parentScope.launch {
            if (library.markAllWatched(profileId, meta, watched)) addonEvents?.titleMarked(meta.type, meta.id, watched)
            trackerMarks?.onMarked(WatchedMark.Title(meta.id, meta.type, meta, watched))
        }
    }

    private fun markEpisodeWatched(videoId: String, watched: Boolean) {
        val meta = meta ?: return
        followsCurrent = false
        parentScope.launch {
            if (library.markVideoWatched(profileId, meta, videoId, watched)) addonEvents?.videosMarked(meta.type, meta.id, listOf(videoId), watched)
            trackerMarks?.onMarked(WatchedMark.Video(meta, videoId, watched))
        }
    }

    /** Carries out an entry of the episode [videoId]'s menu ([DetailEpisode.menu]). */
    fun episodeMenuAction(videoId: String, action: MediaAction) {
        when (action) {
            MediaAction.PLAY_FROM_START -> playFromStart(videoId)
            MediaAction.MARK_WATCHED -> markEpisodeWatched(videoId, watched = true)
            MediaAction.MARK_UNWATCHED -> markEpisodeWatched(videoId, watched = false)
            MediaAction.MARK_SEASON_WATCHED, MediaAction.MARK_SEASON_UNWATCHED -> {
                val season = meta?.video(videoId)?.seriesInfo?.season ?: return
                markSeasonWatched(season, watched = action == MediaAction.MARK_SEASON_WATCHED)
            }
            MediaAction.CHOOSE_STREAM -> chooseEpisodeStream(videoId)
            MediaAction.DOWNLOAD -> episodeDownloadAction(videoId, DetailDownloads.DOWNLOAD)
            MediaAction.PAUSE_DOWNLOAD -> episodeDownloadAction(videoId, DetailDownloads.PAUSE)
            MediaAction.RESUME_DOWNLOAD -> episodeDownloadAction(videoId, DetailDownloads.RESUME)
            MediaAction.RETRY_DOWNLOAD -> episodeDownloadAction(videoId, DetailDownloads.RETRY)
            MediaAction.CANCEL_DOWNLOAD -> episodeDownloadAction(videoId, DetailDownloads.CANCEL)
            MediaAction.DELETE_DOWNLOAD -> episodeDownloadAction(videoId, DetailDownloads.DELETE)
            MediaAction.TRAILER -> {
                val video = meta?.video(videoId) ?: return
                trailers(video.trailerStreams).firstOrNull()?.let { openEpisodeTrailer(videoId, it.id) }
            }
            MediaAction.REMOVE_FROM_CONTINUE_WATCHING, MediaAction.DETAILS, MediaAction.ADD_TO_LIBRARY, MediaAction.REMOVE_FROM_LIBRARY,
            MediaAction.ADD_TO_WATCHLIST, MediaAction.REMOVE_FROM_WATCHLIST, MediaAction.WATCHLISTS,
            -> error("an episode's menu has no $action")
        }
    }

    /** Carries out an entry of the season [season]'s menu ([DetailSeason.menu]): its mark, or the whole title's. */
    fun seasonMenuAction(season: Int, action: MediaAction) {
        when (action) {
            MediaAction.MARK_SEASON_WATCHED, MediaAction.MARK_SEASON_UNWATCHED -> markSeasonWatched(season, watched = action == MediaAction.MARK_SEASON_WATCHED)
            MediaAction.MARK_WATCHED, MediaAction.MARK_UNWATCHED -> markWatched(watched = action == MediaAction.MARK_WATCHED)
            else -> error("a season's menu has no $action")
        }
    }

    /** Marks the episodes of [season] watched (those out so far) or unwatched. */
    fun markSeasonWatched(season: Int, watched: Boolean) {
        val meta = meta ?: return
        followsCurrent = false
        parentScope.launch {
            val changed = library.markSeasonWatched(profileId, meta, season, watched)
            addonEvents?.videosMarked(meta.type, meta.id, changed, watched)
            trackerMarks?.onMarked(WatchedMark.Season(meta, season, watched))
        }
    }

    /**
     * Rates the title [score] (1–10) on the profile's trackers that rate it, or with `null` removes the rating
     * ([DetailUiState.userRating]). Each line shows the score being saved at once, then what its write came to; a read
     * still answering is dropped, being older. The writes don't depend on the page: leaving it stops none.
     */
    fun rate(score: Int?) {
        val meta = meta ?: return
        val trackers = coordinator ?: return
        val lines = userScores?.takeIf { it.isNotEmpty() } ?: return
        scoresJob?.cancel()
        rateJob?.cancel()
        userScores = lines.map { UserScore(it.service, score, UserScoreState.SAVING) }
        publish()
        rateJob = scope.launch {
            trackers.rate(meta, score).collect {
                userScores = it
                publish()
            }
        }
    }

    /**
     * The Watchlist action ([DetailUiState.watchlist]): with one tracker's list, puts the title on it or takes it off,
     * the app's notice saying when that didn't go through; with several, opens the Watchlist sheet over the page. The
     * writes don't depend on the page: leaving it stops none.
     */
    fun watchlist() {
        val meta = meta ?: return
        val actions = actions ?: return
        val lines = watchlistLines?.takeIf { it.isNotEmpty() } ?: return
        val line = lines.singleOrNull()
        if (line == null) {
            actions.openWatchlists(WatchlistRequest(meta.type, meta.id, meta.preview.name, meta))
        } else {
            actions.setWatchlisted(meta.type, meta.id, meta.preview.name, meta, line.service, listed = line.listed != true)
        }
    }

    /**
     * Answers the "Finished watching?" prompt ([DetailUiState.playerApp]): [finished] marks the video handed off watched
     * as [markWatched] or [markEpisodeWatched] would (the trackers and sync follow), the season and the list following
     * the current episode as after the player; either way the prompt goes.
     */
    fun answerPlayerApp(finished: Boolean) {
        val prompt = (playerAppTitle as? PlayerAppTitle.Asking)?.prompt ?: return
        if (finished) {
            val meta = meta ?: return
            if (meta.videos.isEmpty() || videoIsTitle(meta.id, prompt.videoId)) markWatched(true) else markEpisodeWatched(prompt.videoId, true)
            followsCurrent = true
        }
        playerApps?.answered(prompt.metaId)
        playerAppTitle = null
        publish()
    }

    /** Asks every addon again; answers still fresh in the response cache come from there. */
    fun reload() = load()

    /**
     * The blocked page's "Ask a parent" ([DetailBody.Blocked]): a parent's PIN, then the title joins the profile's
     * allowed titles and the page loads.
     */
    fun askParent() {
        val kids = kids ?: return
        if (kidsVerdict !is KidsVerdict.Blocked) return
        val name = knownName()
        val title = kids.titleOf(type, id, judged(), name ?: id)
        host.askParent(ParentCheck.Allow(name)) { kids.allow(title) }
    }

    /** "Block on this profile" ([DetailUiState.kidsBlockAction]): a parent's PIN, then the title joins the blocked titles. */
    fun blockOnProfile() {
        val kids = kids ?: return
        if (!kids.active.value || kidsVerdict != KidsVerdict.Allowed) return
        val title = kids.titleOf(type, id, judged(), titleName())
        host.askParent(ParentCheck.Block(title.name)) { kids.block(title) }
    }

    /** Waits for the title's first verdict, loads it when allowed, then follows the gate's changes. */
    private fun judgeFirst(kids: KidsGate) {
        scope.launch {
            kidsVerdict = kids.verdict(type, id, catalogItem)
            load()
            publish()
            kids.changes.collect { if (judge() || relatedCards.isNotEmpty()) publish() }
        }
    }

    /**
     * The title's verdict again, as the gate knows it now (a parent's change, the meta's age ratings); loads the title
     * when it is allowed for the first time. True when the verdict changed.
     */
    private fun judge(): Boolean {
        val kids = kids ?: return false
        val verdict = kids.verdictNow(type, id, judged()) ?: return false
        if (verdict == kidsVerdict) return false
        kidsVerdict = verdict
        if (loadJob == null) load()
        return true
    }

    /** What the gate judges: the meta once loaded, with the age ratings the catalog item sent too, else the catalog item. */
    private fun judged(): MetaItemPreview? {
        val loaded = meta?.preview ?: return catalogItem
        val sent = catalogItem?.certifications.orEmpty()
        return if (sent.isEmpty()) loaded else loaded.copy(certifications = (sent + loaded.certifications).distinct())
    }

    /** The title's name for a parent's PIN and lists: the meta's, the catalog item's, the library's, else its id. */
    private fun titleName(): String = knownName() ?: id

    /** The title's name as far as it is known: the meta's, the catalog item's or the library's; null before any loads. */
    private fun knownName(): String? = listOfNotNull(meta?.preview?.name, catalogItem?.name, item?.name).firstOrNull { it.isNotBlank() }

    /** Names the title after [meta] in the profile's allowed titles, where a parent allowed it by its id alone. */
    private fun nameAllowed(meta: MetaItem) {
        namedFrom = meta
        val kids = kids?.takeIf { it.active.value } ?: return
        val title = kids.titleOf(type, id, judged(), meta.preview.name)
        scope.launch { kids.nameAllowed(title) }
    }

    /** Opens a "More like this" title ([DetailUiState.related]) on a Detail page of its own. */
    fun openRelated(card: MetaCard) = host.openMeta(card.type, card.id, card.preview)

    /** Carries out an entry of a related title's menu ([MetaCard.menu]) as a catalog's cards do. */
    fun relatedMenuAction(card: MetaCard, action: MediaAction) {
        actions?.perform(card.snapshot(), videoId = null, action, host)
    }

    fun release() {
        scope.cancel()
    }

    /**
     * Asks the addons for the title, once the kids gate allows it ([kidsVerdict]; always without a gate): a title it
     * blocks, or whose verdict isn't known yet, asks nothing. [dropAction]: an action still waiting is dropped, not done
     * once the title loads.
     */
    private fun load(dropAction: Boolean = false) {
        if (kidsVerdict != KidsVerdict.Allowed) return
        if (dropAction) videoActionDone = true
        loadJob?.cancel()
        loadJob = scope.launch {
            addonsUnreadable = false
            addons = readingAddons(TAG) { installedAddons() } ?: run {
                addonsUnreadable = true
                publish()
                return@launch
            }
            val titles = animeTitles
            val entry = entry
            findingTitle = titles != null && entry != null
            titlesUnavailable = false
            // The titles it is are found while the addons are asked for the entry itself (the anime datasets take a
            // while to load), and dropped when one of them has it.
            val episode = (videoId?.let(StremioId::parse) as? StremioId.Anime)?.episode
            val entryTitles = if (titles != null && entry != null) {
                async { withTimeoutOrNull(ENTRY_TITLES_WAIT) { titles.of(type, entry.copy(episode = episode)) } }
            } else {
                null
            }
            val note = entryTitles?.let {
                launch {
                    delay(ENTRY_NOTE_AFTER)
                    findingLong = true
                    // A title an addon already has shows no note: no page to build again (a long series' takes a while).
                    if (meta == null) publish()
                }
            }
            aggregator.meta(addons, type, id).collect { states ->
                metaStates = states
                addonsKnown = true
                publish()
            }
            if (entryTitles == null) return@launch
            val opened = meta == null && nothingFound() && entryTitles.await().let { found ->
                titlesUnavailable = found == null
                found != null && openEntryTitle(found)
            }
            note?.cancel()
            if (opened) return@launch
            entryTitles.cancel()
            findingTitle = false
            findingLong = false
            publish()
        }
    }

    /**
     * Shows, in this screen's place, the first of [titles] (an entry's, [AnimeEntryTitles]) that an installed addon has
     * details for, a series at the entry's episode (or [videoId]'s) when that title lists it; false when no addon has
     * any of them.
     */
    private suspend fun openEntryTitle(titles: List<EntryTitle>): Boolean {
        for (title in titles) {
            val found = aggregator.firstMeta(addons, title.type, title.id) ?: continue
            host.showTitle(id, title.type, title.id, title.videoId?.takeIf { video -> found.video(video) != null }, found.preview)
            return true
        }
        return false
    }

    private fun publish() {
        val ready = metaStates.firstNotNullOfOrNull { (it.state as? Loadable.Ready)?.content }
        val asking = metaStates.any { it.state == Loadable.Loading }
        // Offline the title saved with its downloads stands in, whatever an addon still answers; online once every
        // addon failed.
        meta = savedMeta?.takeIf { !online } ?: ready ?: meta?.takeIf { asking } ?: savedMeta?.takeIf { (addonsKnown || addonsUnreadable) && !asking }
        showingSaved = meta != null && meta === savedMeta
        followReleases(meta)
        if (meta != null) judge()
        if (hidden) {
            stale = true
        } else {
            stale = false
            mutableState.value = buildState()
        }
        val shown = meta?.takeIf { kidsVerdict == KidsVerdict.Allowed }
        if (shown != null && shown !== ratingsMeta) loadRatings(shown)
        if (shown != null && shown !== namedFrom) nameAllowed(shown)
        if (shown != null && scoresFor != shown.id) loadUserScores(shown)
        if (shown != null && watchlistFor != shown.id) loadWatchlist(shown)
        if (shown != null && relatedFor != shown.id) loadRelated(shown)
        if (shown != null && !hidden && !showingSaved && itemKnown && profile != null) askStreams(shown, mutableState.value.primaryAction?.videoId)
        if (shown != null && !videoActionDone) openVideo(shown)
    }

    /** Publishes again at the first release after now among [meta]'s videos, the next instant the page changes alone. */
    private fun followReleases(meta: MetaItem?) {
        if (meta === releasesOf) return
        releasesOf = meta
        releaseJob?.cancel()
        val now = clock.now()
        val next = meta?.let { it.releases.getOrNull(releasedBy(it, now)) }
        releaseJob = next?.let {
            scope.launch {
                delay(it - now)
                releasesOf = null
                publish()
            }
        }
    }

    /**
     * Asks the stream addons for [videoId] ([asked]) unless they are being asked already; a request for another video
     * that no press took is cancelled. A video the device has downloaded isn't asked for: Play plays the download.
     */
    private fun askStreams(meta: MetaItem, videoId: String?) {
        val previous = asked
        if (previous?.videoId == videoId) return
        if (previous != null && !previous.taken) previous.job.cancel()
        asked = null
        if (videoId == null || downloaded(videoId)) return
        val job = Job(scope.coroutineContext[Job])
        val asking = AskedStreams(videoId, job)
        asked = asking
        scope.launch(job) {
            aggregator.streams(addons, meta, videoId).collect { states ->
                asking.states.value = states
                if (states.none { it.state == Loadable.Loading }) {
                    asking.none = states.none { (it.state as? Loadable.Ready)?.content?.isNotEmpty() == true }
                    publish()
                }
            }
            if (asking.states.value == null) {
                asking.states.value = emptyList()
                asking.none = true
                publish()
            }
        }
    }

    /**
     * [asked]'s answers for [videoId] for one press, as they come until every addon has answered; null when they are
     * of another video or a press read them already (the press then asks again).
     */
    private fun takeAnswers(videoId: String): Flow<List<AddonLoadable<List<Stream>>>>? {
        val asking = asked?.takeIf { it.videoId == videoId && !it.taken } ?: return null
        asking.taken = true
        return asking.states.filterNotNull().transformWhile { states ->
            emit(states)
            states.any { it.state == Loadable.Loading }
        }
    }

    private fun downloaded(videoId: String): Boolean = titleDownloads.any { it.videoId == videoId && it.status == DownloadStatus.COMPLETED }

    /**
     * Does [videoAction], once, on [meta], the title shown (the saved one too: a download plays, the rest asks the
     * addons as its press would). Opening [videoId]'s stream list needs only the title; playing it waits for the
     * profile's settings, and [DetailVideoAction.PLAY] for the library item too (whether the video resumes). A card's
     * menu entry ([DetailVideoAction.PLAY_FROM_START] and the rest) waits for both, runs on [videoId] or else the video
     * Play plays, and for a movie's Trailer plays its first trailer.
     */
    private fun openVideo(meta: MetaItem) {
        val profile = profile
        val videoId = videoId
        when (videoAction) {
            DetailVideoAction.STREAMS -> {
                videoActionDone = true
                if (videoId != null) openList(meta, videoId, notice = null)
            }
            DetailVideoAction.PLAY -> {
                if (profile == null || !itemKnown) return
                videoActionDone = true
                if (videoId != null) playLocalOr(meta, videoId) { playStreamed(meta, videoId) }
            }
            DetailVideoAction.AUTO_PLAY_LINK -> {
                if (profile == null) return
                videoActionDone = true
                if (videoId == null) return
                val mode = profile.streamAutoPlay.takeIf { it != AutoPlayMode.OFF } ?: AutoPlayMode.REUSE_LAST_LINK
                playLocalOr(meta, videoId) { autoPlay(meta, videoId, profile.copy(streamAutoPlay = mode), fromStart = false) }
            }
            DetailVideoAction.PLAY_FROM_START, DetailVideoAction.CHOOSE_STREAM, DetailVideoAction.DOWNLOAD, DetailVideoAction.TRAILER -> {
                if (!itemKnown || profile == null) return
                videoActionDone = true
                if (videoAction == DetailVideoAction.TRAILER) {
                    trailers(meta.preview.trailerStreams).firstOrNull()?.let { openTrailer(it.id) }
                    return
                }
                val target = videoId ?: mutableState.value.primaryAction?.videoId ?: return
                when (videoAction) {
                    DetailVideoAction.PLAY_FROM_START -> playFromStart(target)
                    DetailVideoAction.CHOOSE_STREAM -> chooseEpisodeStream(target)
                    else -> episodeMenuAction(target, MediaAction.DOWNLOAD)
                }
            }
        }
    }

    /**
     * Asks the trackers for [meta]'s scores, again when another answer (an addon's, a reload) is shown: the answers
     * come from their cache then. The scores already shown stay until the new ones come.
     */
    private fun loadRatings(meta: MetaItem) {
        val ratings = trackerRatings ?: return
        ratingsMeta = meta
        ratingsJob?.cancel()
        ratingsJob = scope.launch {
            ratings(meta).collect {
                trackerScores = it
                publish()
            }
        }
    }

    /**
     * Reads the user's own score of [meta] on the profile's trackers: once per title, and again once the device is
     * back online when a tracker couldn't be read. Not while a rating is being saved: its lines are newer.
     */
    private fun loadUserScores(meta: MetaItem) {
        val trackers = coordinator ?: return
        if (rateJob?.isActive == true) return
        scoresFor = meta.id
        scoresJob?.cancel()
        scoresJob = scope.launch {
            trackers.userScores(meta).collect {
                userScores = it
                publish()
            }
        }
    }

    /** Follows [meta]'s place on the profile's tracker lists, once per title. */
    private fun loadWatchlist(meta: MetaItem) {
        val trackers = coordinator ?: return
        watchlistFor = meta.id
        watchlistJob?.cancel()
        watchlistJob = scope.launch {
            trackers.watchlist(meta.type, meta.id, meta).collect {
                watchlistLines = it
                publish()
            }
        }
    }

    /** Asks the sources of "More like this" for [meta]: once per title, again once the network is back if none answered. */
    private fun loadRelated(meta: MetaItem) {
        val titles = relatedTitles ?: return
        relatedFor = meta.id
        relatedJob?.cancel()
        relatedJob = scope.launch {
            titles.related(addons, meta).collect {
                related = it
                relatedCards = (it as? RelatedTitles.Result.Answered)?.titles?.map { title -> title.toCard() }.orEmpty()
                publish()
            }
        }
    }

    /** The "More like this" row: null until asked, and when no source answered; the same instance while unchanged. */
    private fun relatedRow(previous: DetailRelated?): DetailRelated? {
        val row = when (related) {
            null, RelatedTitles.Result.None -> return null
            RelatedTitles.Result.Loading -> DetailRelated(T.detail.related.title, loading = true, cards = emptyList(), emptyText = null)
            is RelatedTitles.Result.Answered -> {
                val walk = relatedPaging.walk(relatedCards)
                val cards = walk?.shown ?: relatedCards
                DetailRelated(
                    T.detail.related.title,
                    loading = cards.isEmpty() && walk?.judging == true,
                    cards = cards.marked(libraryMarks, downloads = null, relatedWatchlists, caption = profile?.hidePosterCaptions != true),
                    emptyText = if (cards.isEmpty() && walk?.judging != true) T.detail.related.noneFound else null,
                )
            }
        }
        return previous?.takeIf { it == row } ?: row
    }

    private fun buildState(): DetailUiState {
        blocked()?.let { return emptyPage(it, sources = emptyList()) }
        downloadPress = null
        val meta = meta
        val previous = built
        val sources = metaStates.map { source(it, meta) }.reusing(previous?.sources)
        if (meta == null) {
            val loading = !addonsUnreadable && (!addonsKnown || findingTitle || metaStates.any { it.state == Loadable.Loading })
            val body = if (loading) {
                // A kids profile's title shows nothing of itself until its verdict is known.
                previewItem?.takeIf { kidsVerdict == KidsVerdict.Allowed }?.let {
                    DetailBody.Preview(header(it, linked = false, sourceFacts = emptyList()), episodesPending = it.type == SERIES)
                } ?: DetailBody.Loading
            } else {
                DetailBody.Failed(
                    errorText(),
                    failures().map { (name, why) -> T.detail.sourceError(isolate(name), why) }.ifEmpty { null }?.joinToString("\n"),
                )
            }
            return emptyPage(body, sources, loadingNote = T.detail.findingAnime.takeIf { loading && findingLong }).also { built = it }
        }
        val now = clock.now()
        val released = releasedBy(meta, now)
        // Read through the library's rule, so a state another writer left on a watched episode shows where it rests.
        val item = item?.let { settledItem(it, meta, released, now) }
        val marks = marksFor(meta, item)
        val autoPlay = profile?.streamAutoPlay?.let { it != AutoPlayMode.OFF } == true
        val primary = if (itemKnown) {
            primaryAction(meta, item, marks, now)?.let {
                when {
                    picking(it.videoId) -> it.copy(label = T.detail.findingStream, spokenLabel = T.detail.findingStream, busy = true)
                    asked?.takeIf { asking -> asking.videoId == it.videoId }?.none == true && !downloaded(it.videoId) -> it.copy(noStreams = T.streams.empty)
                    else -> it
                }
            }
        } else {
            null
        }
        val inLibrary = item != null && !item.removed
        val hasVideos = meta.videos.isNotEmpty()
        val seasonVideos = seasonsOf(meta)
        val sums = watchedSums(meta, seasonVideos, marks, released, now)
        val watched = if (hasVideos) sums.watched else item?.isWatched == true
        val seasons = seasonVideos.keys.toList()
        followCurrent(meta, seasonVideos, primary?.videoId)
        val selected = selectedSeason(meta, seasons)
        val episodes = if (selected != null) seasonVideos[selected].orEmpty() else meta.videos
        val trailers = trailers(meta.preview.trailerStreams).reusing(previous?.trailers)
        val download = downloads?.takeIf { DetailDownloads.downloadable(meta) }?.let {
            DetailDownloads.title(meta, titleDownloads, syncRule, selected, sums.outEpisodes).also { title -> downloadPress = title.press }
        }
        val share = if (sharing) DetailShare(TitleLink(meta.type, meta.id, meta.preview.name.ifEmpty { null }).toUrl(), T.detail.share, T.addons.details.linkCopied) else null
        // Downloads gone meanwhile (deleted on another screen) are no longer asked about.
        val deleted = deleting?.let { asked -> titleDownloads.filter { asked.season == null || it.season == asked.season } }
        if (deleted?.isEmpty() == true) deleting = null
        val selectedWatched = selected?.let(sums.seasonsWatched::getValue)
        val watchedActionText = when {
            !hasVideos -> if (watched) T.detail.markUnwatched else T.detail.markWatched
            watched -> T.detail.markAllUnwatched
            else -> T.detail.markAllWatched
        }
        val titleMark = MediaMenuEntry(if (watched) MediaAction.MARK_UNWATCHED else MediaAction.MARK_WATCHED, watchedActionText)
        val ranges = ranges(episodes).reusing(previous?.episodeRanges)
        return DetailUiState(
            body = DetailBody.Loaded(header(meta.preview, linked = true, sourceFacts = sourceFacts(meta))),
            sources = sources,
            primaryAction = primary,
            inLibrary = inLibrary,
            libraryActionText = if (inLibrary) T.detail.removeFromLibrary else T.detail.addToLibrary,
            watched = watched,
            watchedActionText = watchedActionText,
            trailers = trailers,
            trailerActionText = if (trailers.size > 1) T.detail.trailers else T.detail.trailer,
            seasons = seasons.map { season ->
                val seasonWatched = sums.seasonsWatched.getValue(season)
                DetailSeason(
                    season = season,
                    label = if (season == 0) T.detail.specials else T.detail.season(season.toString()),
                    selected = season == selected,
                    watched = seasonWatched,
                    menu = listOf(
                        if (seasonWatched) {
                            MediaMenuEntry(MediaAction.MARK_SEASON_UNWATCHED, T.detail.markSeasonUnwatched)
                        } else {
                            MediaMenuEntry(MediaAction.MARK_SEASON_WATCHED, T.detail.markSeasonWatched)
                        },
                        titleMark,
                    ),
                    stateText = if (seasonWatched) T.detail.watched else null,
                )
            }.reusing(previous?.seasons),
            episodes = episodeRows(
                SharedInputs(
                    episodes,
                    T.locale,
                    DetailText.runtimePart(meta.preview.runtime),
                    marks,
                    released,
                    offersDownloads = download != null,
                    chooseStream = autoPlay && primary != null,
                    seasonWatched = selectedWatched,
                    blurUnwatched = profile?.blurUnwatched == true,
                ),
                item,
                nextUp = primary?.videoId,
                now,
            ),
            chooseStreamText = if (autoPlay && primary != null) T.detail.chooseStream else null,
            download = download?.download,
            downloadNotice = downloadNotice,
            downloadDelete = deleted?.takeIf { it.isNotEmpty() }?.let { DownloadRows.deleteDialog(meta.preview.name, it) },
            offlineNote = if (showingSaved) T.offline.detail else null,
            playerApp = playerAppTitle?.let(::playerApp),
            currentEpisode = currentEpisode?.takeIf { meta.lists(selected, it) },
            shownEpisode = episodeToShow?.takeIf { meta.lists(selected, it) },
            shownEpisodeFromEntry = entryToShow && episodeToShow?.let { meta.lists(selected, it) } == true,
            share = previous?.share?.takeIf { it == share } ?: share,
            episodeRanges = ranges,
            listedRange = listedRange(selected, episodes, ranges),
            // Like the trackers' scores, the item joins the row once a tracker has answered.
            userRating = userScores?.takeIf { lines -> lines.any { it.state != UserScoreState.READING } }?.let(DetailRatings::user),
            watchlist = watchlistLines?.takeIf { it.isNotEmpty() && kids?.active?.value != true }?.let { lines ->
                DetailWatchlist(watchlistLabel(lines), listed = lines.any { it.listed == true })
            },
            related = relatedRow(previous?.related),
            kidsBlockAction = if (kids?.active?.value == true) T.kids.block else null,
        ).also { built = it }
    }

    /** Why this kids profile doesn't show the title; null while it isn't blocked. */
    private fun blocked(): DetailBody.Blocked? {
        val reason = (kidsVerdict as? KidsVerdict.Blocked)?.reason ?: return null
        val blocked = T.kids.blocked
        return DetailBody.Blocked(
            title = blocked.title,
            reason = when (reason) {
                is KidsBlockReason.Suggested -> blocked.suggested(isolate(reason.age.toString()))
                is KidsBlockReason.ByAddon -> blocked.byAddon
                KidsBlockReason.NotRated -> blocked.notRated
                KidsBlockReason.ByParent -> blocked.byParent
                KidsBlockReason.Unchecked -> blocked.unchecked
            },
            askLabel = blocked.ask,
        )
    }

    /** The state of [body], one that isn't the title's page ([DetailBody.Loaded]): the page's fields are empty. */
    private fun emptyPage(body: DetailBody, sources: List<DetailSource>, loadingNote: String? = null) = DetailUiState(
        body = body,
        sources = sources,
        primaryAction = null,
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        trailers = emptyList(),
        trailerActionText = T.detail.trailer,
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
        loadingNote = loadingNote,
    )

    /** [meta]'s videos by season, grouped once per title. */
    private fun seasonsOf(meta: MetaItem): Map<Int, List<Video>> {
        if (seasonsMeta !== meta) {
            seasonVideos = meta.videos.filter { it.seriesInfo != null }.groupBy { it.seriesInfo!!.season }
            seasonsMeta = meta
        }
        return seasonVideos
    }

    /**
     * The rows of [shared]'s videos with the library [item] and Play's video [nextUp], each the last build's where its
     * inputs are the same ([builtRows]).
     */
    private fun episodeRows(shared: SharedInputs, item: LibraryItem?, nextUp: String?, now: Instant): List<DetailEpisode> {
        val last = builtRows
        val downloadOf = last?.takeIf { it.downloads === titleDownloads }?.downloadOf
            ?: HashMap<String, Download>().also { map -> titleDownloads.forEach { map.getOrPut(it.videoId) { it } } }
        val pointed = setOfNotNull(item?.state?.videoId, pickVideoId?.takeIf { picking }, nextUp)
        fun inputsOf(video: Video) = episodeInputs(
            video, shared.runtime, item, shared.marks, now, nextUp, downloadOf[video.id], shared.offersDownloads, shared.chooseStream, shared.seasonWatched,
            shared.blurUnwatched,
        )

        // A row built again that comes out as before stays that instance: the UIs skip it by identity.
        fun row(inputs: EpisodeInputs, before: DetailEpisode) = episode(inputs).let { built -> before.takeIf { it == built } ?: built }
        if (last != null && last.shared == shared) {
            // Every other row's inputs are the shared ones and its own video's, as they were.
            val looked = last.pointed + pointed + if (downloadOf === last.downloadOf) emptySet() else last.downloadOf.keys + downloadOf.keys
            val changed = looked.mapNotNull { id -> last.positions[id]?.let { index -> IndexedValue(index, inputsOf(shared.videos[index])) } }
                .filter { (index, inputs) -> inputs != last.inputs[index] }
            if (changed.isEmpty()) {
                builtRows = EpisodeRows(shared, titleDownloads, downloadOf, pointed, last.positions, last.inputs, last.rows)
                return last.rows
            }
            val inputs = last.inputs.toMutableList()
            val rows = last.rows.toMutableList()
            for ((index, its) in changed) {
                inputs[index] = its
                rows[index] = row(its, last.rows[index])
            }
            builtRows = EpisodeRows(shared, titleDownloads, downloadOf, pointed, last.positions, inputs, rows)
            return rows
        }
        val positions = last?.takeIf { it.shared.videos === shared.videos }?.positions
            ?: HashMap<String, Int>(shared.videos.size * 2).also { map -> shared.videos.forEachIndexed { index, video -> map.getOrPut(video.id) { index } } }
        val inputs = shared.videos.map(::inputsOf)
        val rows = if (last == null) {
            inputs.map(::episode)
        } else {
            inputs.map { its ->
                val index = last.positions[its.video.id]
                when {
                    index == null -> episode(its)
                    last.inputs[index] == its -> last.rows[index]
                    else -> row(its, last.rows[index])
                }
            }
        }
        val kept = last?.rows?.takeIf { it == rows } ?: rows
        builtRows = EpisodeRows(shared, titleDownloads, downloadOf, pointed, positions, inputs, kept)
        return kept
    }

    /** [episodes] in ranges of [EPISODE_RANGE] when there are more than that, each named by its first and last episode. */
    private fun ranges(episodes: List<Video>): List<DetailEpisodeRange> {
        if (episodes.size <= EPISODE_RANGE) return emptyList()
        fun number(index: Int) = episodes[index].seriesInfo?.episode?.toString() ?: (index + 1).toString()
        return (episodes.indices step EPISODE_RANGE).map { start ->
            val end = minOf(start + EPISODE_RANGE, episodes.size)
            DetailEpisodeRange(T.detail.episodeRange(number(start), number(end - 1)), start, end)
        }
    }

    /**
     * The index in [ranges] of the range of [season]'s [episodes] the list under the chips holds: the one [listed] for
     * the season, else the one holding [currentEpisode], which it keeps from then on; 0 without ranges.
     */
    private fun listedRange(season: Int?, episodes: List<Video>, ranges: List<DetailEpisodeRange>): Int {
        if (ranges.isEmpty()) return 0
        listed?.takeIf { it.season == season && it.index < ranges.size }?.let { return it.index }
        val current = episodes.indexOfFirst { it.id == currentEpisode }
        val index = ranges.indexOfFirst { current >= it.start && current < it.end }.coerceAtLeast(0)
        listed = ListedRange(season, index)
        return index
    }

    /** The title's playback in a player app as its line on Detail. */
    private fun playerApp(title: PlayerAppTitle): DetailPlayerApp {
        val text = T.playerApps.detail
        return when (title) {
            is PlayerAppTitle.Playing -> {
                val player = isolate(title.player)
                val position = formatTimecode(title.positionMillis / 1000.0)
                val duration = title.durationMillis.takeIf { it > 0 }?.let { formatTimecode(it / 1000.0) }
                val line = when {
                    title.paused && duration != null -> text.pausedOf(player = player, position = position, duration = duration)
                    title.paused -> text.paused(player = player, position = position)
                    duration != null -> text.playingOf(player = player, position = position, duration = duration)
                    else -> text.playing(player = player, position = position)
                }
                DetailPlayerApp(line, markWatchedText = null, notYetText = null)
            }
            // The label is kept with the prompt as it was written, and isolated only here, where it shows.
            is PlayerAppTitle.Asking -> DetailPlayerApp(
                text.finished(player = isolate(title.prompt.player), title = isolate(title.prompt.label)),
                markWatchedText = text.markWatched,
                notYetText = text.notYet,
            )
        }
    }

    private fun source(addonState: AddonLoadable<MetaItem>, shown: MetaItem?): DetailSource {
        val name = addonState.addon.manifest.name
        return when (val loadable = addonState.state) {
            Loadable.Loading -> DetailSource(name, DetailSourceStatus.LOADING, error = null, shown = false)
            is Loadable.Ready -> DetailSource(name, DetailSourceStatus.READY, error = null, shown = loadable.content === shown)
            is Loadable.Error -> DetailSource(name, DetailSourceStatus.FAILED, error = loadable.error.message, shown = false)
        }
    }

    private fun errorText(): String = when {
        addonsUnreadable -> T.addons.unreadable
        titlesUnavailable && nothingFound() -> T.detail.animeListsUnavailable
        metaStates.isEmpty() -> if (entry != null) T.detail.noAnimeAddon else T.detail.noAddons
        nothingFound() -> T.detail.notFound
        else -> T.detail.loadFailed
    }

    /** No addon has the title: none was asked, or each answered that it hasn't. */
    private fun nothingFound(): Boolean = metaStates.all { (it.state as? Loadable.Error)?.error?.let(::hasNot) == true }

    /** The meta addons that failed to answer (not those that answered that they haven't the title), and why. */
    private fun failures(): List<Pair<String, String>> = metaStates.mapNotNull { state ->
        (state.state as? Loadable.Error)?.error?.takeUnless(::hasNot)?.let { state.addon.manifest.name to it.message }
    }

    /**
     * The information's last lines: the meta addon the details shown come from (none for the ones saved with
     * downloads), and the ones that failed.
     */
    private fun sourceFacts(shown: MetaItem): List<DetailFact> {
        val facts = T.detail.facts
        val from = metaStates.firstOrNull { (it.state as? Loadable.Ready)?.content === shown }?.addon?.manifest?.name
        // Several names isolated each; one shows alone.
        val failed = failures().map { it.first }.distinct().ifEmpty { null }?.let { names ->
            names.singleOrNull() ?: names.joinToString(T.detail.listSeparator) { isolate(it) }
        }
        return listOfNotNull(
            from?.let { DetailFact(facts.source, it, linked = false) },
            failed?.let { DetailFact(facts.failed, it, linked = false) },
        )
    }

    private fun marksFor(meta: MetaItem, item: LibraryItem?): WatchedBitField {
        val source = item?.state?.watchedVideos
        val cached = marks
        if (cached != null && marksMeta === meta && marksSource == source) return cached
        return WatchedBitField.of(source, meta).also {
            marks = it
            marksMeta = meta
            marksSource = source
        }
    }

    /**
     * [item] read through the library's rule ([settled]) for [meta] with [released] of its releases out by [now]: read
     * again only when one of them changes.
     */
    private fun settledItem(item: LibraryItem, meta: MetaItem, released: Int, now: Instant): LibraryItem {
        lastSettled?.takeIf { it.from === item && it.meta === meta && it.released == released }?.let { return it.item }
        return item.copy(state = item.state.settled(meta, now)).also { lastSettled = Settled(item, meta, released, it) }
    }

    /**
     * [meta]'s [WatchedSums] with its [seasonVideos] under [marks], [released] of its releases out by [now]: summed again
     * only when one of them changes.
     */
    private fun watchedSums(meta: MetaItem, seasonVideos: Map<Int, List<Video>>, marks: WatchedBitField, released: Int, now: Instant): WatchedSums {
        lastSums?.takeIf { it.meta === meta && it.marks === marks && it.released == released }?.let { return it }
        return WatchedSums(
            meta,
            marks,
            released,
            watched = allWatched(meta.videos, marks, now),
            seasonsWatched = seasonVideos.mapValues { (_, videos) -> allWatched(videos, marks, now) },
            outEpisodes = meta.videos.count { it.seriesInfo != null && it.isOut(now) },
        ).also { lastSums = it }
    }

    /**
     * Sets [currentEpisode] as the page opens, the list brought to it unless it is its season's first, then moves it on
     * with the primary action's video, [primary] now: the season and the list following it ([followsCurrent]) unless
     * another season was chosen, else the season shown kept.
     */
    private fun followCurrent(meta: MetaItem, seasonVideos: Map<Int, List<Video>>, primary: String?) {
        val seen = seenPrimary
        seenPrimary = primary
        val current = currentEpisode
        if (current == null) {
            val opened = (videoId ?: shownVideoId)?.takeIf { meta.video(it) != null } ?: primary ?: return
            currentEpisode = opened
            val list = meta.seasonOf(opened)?.let { seasonVideos[it] } ?: meta.videos
            if (chosenSeason == null && list.indexOfFirst { it.id == opened } > 0) {
                episodeToShow = opened
                entryToShow = opened == shownVideoId
                listed = null
            }
            return
        }
        if (primary == null || seen == null || primary == seen || primary == current) return
        val season = meta.seasonOf(primary)
        val shown = chosenSeason ?: keptSeason ?: meta.seasonOf(current)
        currentEpisode = primary
        when {
            !followsCurrent -> if (chosenSeason == null && season != shown) keptSeason = shown
            chosenSeason == null || chosenSeason == season -> {
                chosenSeason = null
                keptSeason = null
                episodeToShow = primary
                entryToShow = false
                listed = null
            }
        }
    }

    /** The season to show: the one chosen, else [currentEpisode]'s, else the first regular season, or the first. */
    private fun selectedSeason(meta: MetaItem, seasons: List<Int>): Int? {
        if (seasons.isEmpty()) return null
        return chosenSeason?.takeIf { it in seasons }
            ?: keptSeason?.takeIf { it in seasons }
            ?: currentEpisode?.let { meta.seasonOf(it) }?.takeIf { it in seasons }
            ?: seasons.firstOrNull { it != 0 }
            ?: seasons.first()
    }

    /**
     * The header of [preview] with [sourceFacts] last, built again only when one of them or the trackers' scores change;
     * [linked]: its ratings open their links.
     */
    private fun header(preview: MetaItemPreview, linked: Boolean, sourceFacts: List<DetailFact>): DetailHeader {
        val cached = lastHeader
        if (cached != null && headerSource === preview && headerScores === trackerScores && headerLinked == linked && headerSourceFacts == sourceFacts) {
            return cached
        }
        return buildHeader(preview, linked, sourceFacts).also {
            lastHeader = it
            headerSource = preview
            headerScores = trackerScores
            headerLinked = linked
            headerSourceFacts = sourceFacts
        }
    }

    /**
     * [streams] as trailers to offer (the title's, or an episode's), in the addon's order: those that play (HTTP) or
     * open outside the app (YouTube, a web page).
     */
    internal fun trailers(streams: List<Stream>): List<DetailTrailer> {
        val offered = streams.withIndex().filter { it.value.playability !is StreamPlayability.Unsupported }
        return offered.mapIndexed { position, (index, _) ->
            DetailTrailer(TRAILER_PREFIX + index, if (offered.size == 1) T.detail.trailer else T.detail.trailerNumbered((position + 1).toString()))
        }
    }

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "detail"

        private const val TAG = "detail"
        private const val TRAILER_PREFIX = "trailer:"
        private const val SERIES = "series"

        /** How long an anime list entry's titles are looked for before the page says what it waits for. */
        internal val ENTRY_NOTE_AFTER: Duration = 3.seconds

        /** How long an anime list entry waits for its titles (the anime datasets' load) before the addons' answers stand. */
        internal val ENTRY_TITLES_WAIT: Duration = 30.seconds

        /** An answer that the addon hasn't the title, which isn't a failure. */
        private fun hasNot(error: AddonError) = error == AddonError.EmptyContent || error == AddonError.Http(404)

        /** This list, with its items equal to [previous]'s at the same place kept as those instances; [previous] when all are. */
        private fun <T> List<T>.reusing(previous: List<T>?): List<T> = when {
            previous == null -> this
            this == previous -> previous
            else -> mapIndexed { index, item -> previous.getOrNull(index)?.takeIf { it == item } ?: item }
        }

        /** The season of this title's video [videoId]; null for one it doesn't list or that isn't an episode. */
        private fun MetaItem.seasonOf(videoId: String): Int? = video(videoId)?.seriesInfo?.season

        /** The list of [season]'s episodes, or of every video without seasons (null), holds [videoId]. */
        private fun MetaItem.lists(season: Int?, videoId: String): Boolean = video(videoId)?.let { season == null || it.seriesInfo?.season == season } == true

        /**
         * How many of [meta]'s release dates are by [now]: which of its videos are out ([isOut]) stays the same until
         * this changes.
         */
        private fun releasedBy(meta: MetaItem, now: Instant): Int =
            // The comparison finds none equal: the result is the insertion point of the first release after now.
            -meta.releases.binarySearch { if (it <= now) -1 else 1 } - 1

        /** Every video of [videos] out by [now] is marked, and there is at least one. */
        private fun allWatched(videos: List<Video>, marks: WatchedBitField, now: Instant): Boolean {
            val out = videos.filter { it.isOut(now) }
            return out.isNotEmpty() && out.all { marks.isWatched(it.id) }
        }
    }
}
