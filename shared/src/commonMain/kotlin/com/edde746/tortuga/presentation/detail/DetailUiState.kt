package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.downloads.DownloadDeleteDialog
import com.edde746.tortuga.presentation.menu.MediaMenuEntry
import com.edde746.tortuga.presentation.menu.MediaMenuHost
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.ratings.RatingSource

/**
 * The Detail screen (stremio-core `MetaDetails`): one title from the installed meta addons, with its library state,
 * seasons and episodes. Every text is ready to show.
 *
 * @param body what the screen shows ([DetailBody]): the title's page (or its preview), why it is blocked, why it
 *   failed, or that it loads. The fields after it are the page's: empty on every other body, and on a preview, which
 *   draws the page with nothing of it but its header.
 * @param sources every meta addon asked, in the installed order, with its own loading or error state.
 * @param primaryAction "12:34" or "S1E3 · 12:34" to resume (its play symbol and watched fill say it resumes), "S1E4" to
 *   play an episode, "Play"; null until the library state is read, and for items with videos that aren't episodes and
 *   nothing to resume (unless the item names a default video, or is live).
 * @param inLibrary in the user's library ([DetailStore.addToLibrary], [DetailStore.removeFromLibrary]).
 * @param libraryActionText the label of the library toggle: "Add to library" or "Remove from library".
 * @param watched a movie watched at least once; for a series, every episode out so far marked watched.
 * @param watchedActionText the label of [DetailStore.markWatched] with `!watched`.
 * @param trailers the trailers to offer (one opens at once, [singleTrailer]; several open a menu), in the addon's order:
 *   YouTube videos and web pages open outside the app, HTTP streams play in the player ([DetailStore.openTrailer]).
 * @param trailerActionText the label of the trailer action: "Trailer" for one, "Trailers" for several.
 * @param seasons the seasons, in the title's order (specials last), as a row of chips on every UI; empty for items
 *   without seasons. On a TV (a D-pad) a season's chip selects its season as focus moves onto it
 *   ([DetailStore.selectSeason]): the next season is one Right; elsewhere a tap, a click or Select picks it. Each chip's
 *   menu (a held Select on a TV, a long press, a context menu or a secondary click elsewhere) is its
 *   [DetailSeason.menu].
 * @param episodes the selected season's episodes, or every video of an item without seasons (a channel).
 * @param episodeRanges the selected season's [episodes] in ranges of [EPISODE_RANGE] when it has more than that (a
 *   long anime season): a row of chips under the seasons' chips on every UI. A TV's episode row keeps the whole season,
 *   so the next season stays one Right away: a range's chip brings the row to the range's first episode as focus moves
 *   onto it, and the chip of the range holding the row's episode ([rangeOf]) shows selected. Elsewhere the list under
 *   the chips holds [listedRange]'s episodes alone ([listedEpisodes]). Empty otherwise.
 * @param listedRange off a TV, the index in [episodeRanges] of the range the list under the chips holds, its chip shown
 *   selected: the one picked last ([DetailStore.pickRange]), else the one holding [currentEpisode]. A pick holds while
 *   the current episode moves on by a mark made on the page; another season, and the list brought to an episode
 *   ([shownEpisode]: the season chosen again, back from the player having finished one), give way to the range holding
 *   the current episode again. 0 without ranges.
 * @param chooseStreamText with auto-play on, the label of the actions that open the stream list instead: for the
 *   primary action's video ([DetailStore.chooseStream]) and in each episode's menu (its Choose a stream entry):
 *   "Choose a stream"; null with auto-play off (Play and the episodes open the list then, but for a video that resumes,
 *   which replays the stream it last played from) or without a primary action.
 * @param download the action row's download button and its menu; null where this device has no downloads (Apple TV)
 *   or the title can't be downloaded (a channel, a title without episodes or a video of its own).
 * @param downloadNotice what came of a "Download…" choice ("4 episodes added to Downloads", "Every episode is already
 *   downloaded or queued."): one line to show until [DetailStore.dismissDownloadNotice]; null otherwise.
 * @param downloadDelete the question before a season's or the whole title's downloads go ([DetailStore.downloadAction]'s
 *   Delete this season's downloads and Delete all downloads), answered with [DetailStore.confirmDownloadDelete] or
 *   [DetailStore.dismissDownloadDelete]; null otherwise.
 * @param offlineNote the title shown is the one saved with its downloads (the device is offline, or no addon could
 *   load it): "Showing the details saved with your downloads."; null otherwise.
 * @param playerApp the title's playback in a player app: the line while a live player plays it, or the "Finished
 *   watching?" prompt after one that reports nothing; null otherwise.
 * @param currentEpisode the current episode when the selected season lists it: the one the primary action plays (in
 *   progress, else next up), or the video the page was opened at (a link's, Continue Watching's, an anime list entry's
 *   first episode: [com.edde746.tortuga.presentation.navigation.Route.Detail.shownVideoId]) until the primary action
 *   moves on. Focus moving into the episodes with a D-pad lands on it; null otherwise.
 * @param shownEpisode the episode the UI brings the list to, once, its middle at the middle of what shows of the list,
 *   where the episodes are beside the details (wide windows, TV): the current episode as the page opens (unless it is
 *   its season's first, in view at the list's top), when the user chooses its season again, and when it moves on other
 *   than by the page's own marks (back from the player after finishing one) unless the user chose another season; null
 *   once the UI has brought the list there ([DetailStore.episodeShown]) or another season is chosen.
 * @param shownEpisodeFromEntry [shownEpisode] is the first episode of the anime list entry the page opened as
 *   ([com.edde746.tortuga.presentation.navigation.Route.Detail.shownVideoId]), as the page opens: the one-column page
 *   (phones, narrow windows), which brings its list to no other episode, brings it into view, Compose to the top of
 *   what shows under the back button, Apple to the middle.
 * @param share the title's link to share ([TitleLink]), for every title once it is known; null where the device has no
 *   Share (TVs).
 * @param userRating the user's own score of the title on the profile's trackers; null until they are known, and when
 *   none of them rates the title (no tracker connected, or none that takes it).
 * @param watchlist the title on the profile's tracker lists ([DetailWatchlist]); null until the trackers that take it
 *   are known, when none does (no tracker connected, or none that takes the title), and on a kids profile.
 * @param related the "More like this" row ([DetailRelated]); null when no source has answered for the title: none
 *   applies (no addon `similar` catalog of its type, no IMDb id for Trakt) or every one failed.
 * @param kidsBlockAction "Block on this profile" ([DetailStore.blockOnProfile]) on a title a kids profile allows, the
 *   action row's [DetailAction.KIDS_BLOCK]; null on any other profile.
 * @param loadingNote while the title loads ([DetailBody.Loading], [DetailBody.Preview]), what it has waited seconds
 *   for, shown under the spinner or in the preview's actions' place: "Looking this anime up in the anime lists…" (an
 *   anime list entry's titles, the anime datasets downloading or loading); null otherwise.
 */
data class DetailUiState(
    val body: DetailBody,
    val sources: List<DetailSource>,
    val primaryAction: DetailPrimaryAction?,
    val inLibrary: Boolean,
    val libraryActionText: String,
    val watched: Boolean,
    val watchedActionText: String,
    val trailers: List<DetailTrailer>,
    val trailerActionText: String,
    val seasons: List<DetailSeason>,
    val episodes: List<DetailEpisode>,
    val chooseStreamText: String?,
    val download: DetailDownload? = null,
    val downloadNotice: String? = null,
    val downloadDelete: DownloadDeleteDialog? = null,
    val offlineNote: String? = null,
    val playerApp: DetailPlayerApp? = null,
    val currentEpisode: String? = null,
    val shownEpisode: String? = null,
    val shownEpisodeFromEntry: Boolean = false,
    val share: DetailShare? = null,
    val episodeRanges: List<DetailEpisodeRange> = emptyList(),
    val listedRange: Int = 0,
    val userRating: DetailUserRating? = null,
    val watchlist: DetailWatchlist? = null,
    val related: DetailRelated? = null,
    val kidsBlockAction: String? = null,
    val loadingNote: String? = null,
) {
    /** The page has an episodes' column: the title's [seasons] or [episodes], or a series' still to come (a preview). */
    val hasEpisodes: Boolean
        get() = seasons.isNotEmpty() || episodes.isNotEmpty() || (body as? DetailBody.Preview)?.episodesPending == true

    /** The page shows only its header ([DetailBody.Preview]): the rest comes with the title. */
    val preview: Boolean
        get() = body is DetailBody.Preview

    /** The TV page's episodes section: the title has loaded with [seasons] or [episodes] (a preview has none yet). */
    val listsEpisodes: Boolean
        get() = body is DetailBody.Loaded && (seasons.isNotEmpty() || episodes.isNotEmpty())

    /** The selected season's number ([DetailSeason.season]); null without seasons. */
    val selectedSeason: Int?
        get() = seasons.firstOrNull { it.selected }?.season

    /** The selected season's index in [seasons]; 0 without one. */
    val selectedSeasonIndex: Int
        get() = seasons.indexOfFirst { it.selected }.coerceAtLeast(0)

    /** [currentEpisode]'s index in [episodes]; 0 when they don't list it. */
    val currentEpisodeIndex: Int
        get() = episodes.indexOfFirst { it.videoId == currentEpisode }.coerceAtLeast(0)

    /** The index in [episodeRanges] of the range holding the episode at [index] of [episodes]; 0 for one in none. */
    fun rangeOf(index: Int): Int = episodeRanges.indexOfFirst { index >= it.start && index < it.end }.coerceAtLeast(0)

    /** The episodes the list under the chips holds off a TV: [listedRange]'s, or every one of [episodes] without ranges. */
    val listedEpisodes: List<DetailEpisode>
        get() = episodeRanges.getOrNull(listedRange)?.let { episodes.subList(it.start, it.end) } ?: episodes

    /** The index in [episodes] of [listedEpisodes]' first. */
    val listedStart: Int
        get() = episodeRanges.getOrNull(listedRange)?.start ?: 0

    /** The page shows its metadata block: the title's metadata line, its ratings or the user's own score. */
    val showsMetaLine: Boolean
        get() = (body as? DetailBody.Page)?.header?.let { it.infoLine != null || it.ratings.isNotEmpty() } == true || userRating?.text != null

    /** The TV page's Cast & crew row: the title has loaded with a cast or a crew (a preview shows none). */
    val listsCast: Boolean
        get() = (body as? DetailBody.Loaded)?.header?.castAndCrew?.isNotEmpty() == true

    /** The trailer the trailer action opens at once: the title's only one; null with several (a menu of them) or none. */
    val singleTrailer: DetailTrailer?
        get() = trailers.singleOrNull()
}

/** How many episodes a range of a long season holds ([DetailUiState.episodeRanges]). */
const val EPISODE_RANGE = 50

/**
 * What the Detail screen shows, one at a time: each UI switches on it without a default, so a body a UI doesn't draw
 * stops its build. Both kinds of [Page] are drawn by one page, which a preview turning into the title keeps.
 */
sealed interface DetailBody {
    /**
     * No addon has answered with the title yet, and some are still asked; the catalog item the title was opened from
     * has nothing the page is laid out by: a spinner.
     */
    data object Loading : DetailBody

    /**
     * Every addon answered and none has the title.
     *
     * @param text what to show instead.
     * @param detail each meta addon that failed (not one that answered it hasn't the title) and why, a line each:
     *   `Cinemeta: The addon returned an error (HTTP 500).`; null when none failed.
     */
    data class Failed(val text: String, val detail: String?) : DetailBody {
        /** The error's title: [text] over a [detail]; null where [text] is the message itself. */
        val title: String?
            get() = text.takeIf { detail != null }

        /** The error's message: [detail], else [text]. */
        val message: String
            get() = detail ?: text
    }

    /** The title's page, drawn from [header]: the title's ([Loaded]) or, while it loads, its preview's ([Preview]). */
    sealed interface Page : DetailBody {
        val header: DetailHeader
    }

    /**
     * No addon has answered with the title yet, and some are still asked: the page drawn from [header], the catalog
     * item's the title was opened from (the same as the addon's for Cinemeta's catalogs), which has the backdrop and
     * logo the page is laid out by, its ratings without their links. It is drawn alone: the backdrop, the logo, the
     * metadata line and the ratings, where the whole page has them, so nothing moves when the rest comes (the actions,
     * the description, the information, the episodes, all empty meanwhile).
     *
     * @param episodesPending the title is a series: the page keeps its episodes' column, empty until they come, as laid
     *   out for a title with seasons or episodes.
     */
    data class Preview(override val header: DetailHeader, val episodesPending: Boolean) : Page

    /** The title's page: [header] the addon's details. */
    data class Loaded(override val header: DetailHeader) : Page

    /**
     * The title is blocked on this kids profile: the screen shows why instead of the page; its button is
     * [DetailStore.askParent].
     *
     * @param title "Not available on this profile".
     * @param reason "Suggested 16+", that its addon rates it for older viewers, "No suggested age", "Blocked by a
     *   parent", or that its suggested age couldn't be checked.
     * @param askLabel "Ask a parent".
     */
    data class Blocked(val title: String, val reason: String, val askLabel: String) : DetailBody
}

/**
 * A range of a long season's episodes: [DetailUiState.episodes] from [start] (inclusive) to [end] (exclusive), [label]
 * its episode numbers, `1–50`.
 */
data class DetailEpisodeRange(val label: String, val start: Int, val end: Int)

/**
 * The Share action: [url] to the share sheet (iOS, iPadOS, macOS, Android phones and tablets), or to the clipboard
 * where there is none (Windows and Linux), [copiedText] ("Link copied") confirming it. TVs don't offer it: they have
 * no share sheet and no clipboard anyone reads.
 */
data class DetailShare(val url: String, val label: String, val copiedText: String)

/**
 * The "More like this" row ([RelatedTitles]): [title] over the titles' cards, as a catalog's shelf, after the episodes and
 * before the information. A card opens its title ([DetailStore.openRelated]); its menu is a catalog card's
 * ([DetailStore.relatedMenuAction]).
 *
 * @param loading the sources are still being asked: a shelf of blank cards.
 * @param cards the titles, best first; empty while [loading], and when the sources answered with none.
 * @param emptyText "No similar titles found." when the sources answered with none; null otherwise.
 */
data class DetailRelated(val title: String, val loading: Boolean, val cards: List<MetaCard>, val emptyText: String?)

/**
 * The title's Watchlist action ([DetailAction.WATCHLIST], behind ⋯ off a TV; [DetailStore.watchlist]): with one tracker
 * whose list takes the title, it toggles that list; with several, it opens the Watchlist sheet
 * ([com.edde746.tortuga.app.AppSessionState.watchlist]), each list toggled on its own. The lists are the services' and
 * apart from the library, and the words say so.
 *
 * @param label the action's words: "Add to Trakt watchlist", "Remove from Trakt watchlist", or with several lists
 *   "Tracker watchlists".
 * @param listed the title is on the list (with several: on at least one) as far as the app knows: a filled mark; never
 *   while that is unknown.
 */
data class DetailWatchlist(val label: String, val listed: Boolean)

/**
 * The user's own score of the title: one score, 1–10, which [DetailStore.rate] sends
 * to every tracker that rates the title ([com.edde746.tortuga.trackers.TrackerCoordinator.rate]). The action row's
 * [DetailAction.RATE] (behind ⋯ off a TV) opens the picker, which lists [trackers]; once there is a score it also ends
 * the ratings row, read-only.
 *
 * @param text the score in the ratings row, "8"; where the trackers' scores differ, their range, "7–8"; null before any
 *   score. A tracker without a score, or whose score is unknown, doesn't count as differing. With a score the picker
 *   offers to remove it.
 * @param label the action's words, also [text] for screen readers: "Rate this title", "Your rating: 8".
 * @param score the picker's selected score: the one score the trackers have; null with none, or when they differ.
 * @param trackers each tracker's line in the picker, in the services' order.
 */
data class DetailUserRating(
    val text: String?,
    val label: String,
    val score: Int?,
    val trackers: List<DetailTrackerScore>,
)

/**
 * One tracker's line in the rating picker: [name] ("Trakt") and [status]: its score ("8", "Not rated") or where its
 * read or write is ("Checking…", "Saving…", "Saved", "Removed", "Couldn't check", "Not sent yet, will retry", "Not
 * saved"). [busy]: a read or write is on its way; [attention]: the score there is unknown or not saved yet.
 */
data class DetailTrackerScore(val service: TrackerService, val name: String, val status: String, val busy: Boolean, val attention: Boolean)

/**
 * The title's playback in a player app (`PlayerAppHandoff`), shown inline under the actions (not a dialog).
 *
 * - While a player that reports live (mpv, VLC on desktop and Mac) plays it: [text] "Playing in mpv · 12:34 of
 *   1:45:00", following the player, "Paused in mpv · 12:34 of 1:45:00" while it is paused; no actions.
 * - After a hand-off to a player that reported nothing (a URL-scheme app, the chooser, an Android app that returned no
 *   position): [text] "Opened in VLC. Finished watching S1E3 · Pilot?" with [markWatchedText] "Mark as watched" and
 *   [notYetText] "Not yet" ([DetailStore.answerPlayerApp]). It stays until answered, or until the title plays again.
 */
data class DetailPlayerApp(val text: String, val markWatchedText: String?, val notYetText: String?)

/**
 * One meta addon's part.
 *
 * @param shown the header comes from this addon: the first in the installed order that has the title.
 * @param error why it has nothing, worded; null while loading or when it answered.
 */
data class DetailSource(val addonName: String, val status: DetailSourceStatus, val error: String?, val shown: Boolean)

enum class DetailSourceStatus { LOADING, READY, FAILED }

/**
 * @param id the title's id, and [imdbId] the IMDb id its addon sent with it (`imdb_id`): what the profile's poster URL
 *   template fills in ([com.edde746.tortuga.presentation.board.PosterTemplate.url]).
 * @param background the backdrop image, drawn full-bleed; with none, the UI falls back to [poster].
 * @param logo the title as an image, shown instead of [title] when present.
 * @param infoLine one metadata line: release info, runtime and the first genres, `1994 · 2 h 22 min · Drama, Crime`.
 * @param ratings the ratings row under [infoLine], in [RatingSource] order, one score per source: the addon's IMDb
 *   rating at once, then whatever the profile's connected trackers have for the title, the row growing as each answers.
 *   Empty when nobody has a score.
 * @param overview the addon's description.
 * @param castLine the first three names of the cast, `With Karl Urban, Jack Quaid, Antony Starr`: TV's title block
 *   shows it under the description; null without a cast.
 * @param castAndCrew the cast, then the directors and the writers, each name once, as [linkGroups] has them: TV's Cast
 *   & crew row, each name opening Search.
 * @param linkGroups genres, cast, directors, writers and the addon's other link categories, in the addon's order, then
 *   the title's website: rows of text links in the information block, each name opening its link.
 * @param factsTitle the heading of [facts]: "Information".
 * @param facts what the addon says about the title, in a fixed order, each only when the addon has it: release date,
 *   runtime, country, language, awards, then the [DetailFact.linked] names: cast, directors, writers, genres; last,
 *   the meta addon the details come from ("Details from" / "Cinemeta") and the ones that failed ("Couldn't load
 *   from"), [DetailUiState.sources] in words.
 */
data class DetailHeader(
    val id: String,
    val type: String,
    val imdbId: String?,
    val title: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val posterShape: PosterShape,
    val infoLine: String?,
    val ratings: List<DetailRating>,
    val overview: String?,
    val linkGroups: List<DetailLinkGroup>,
    val factsTitle: String,
    val facts: List<DetailFact>,
    val castLine: String? = null,
    val castAndCrew: List<DetailLink> = emptyList(),
) {
    /** The information block's plain lines: [facts] but the [DetailFact.linked] ones, which [linkGroups] shows as links. */
    val informationFacts: List<DetailFact> = facts.filterNot { it.linked }

    /** The page shows its information block: it has [informationFacts] or [linkGroups]. */
    val hasInformation: Boolean
        get() = informationFacts.isNotEmpty() || linkGroups.isNotEmpty()

    /** The TV page offers More, the side panel with the rest of the title's information: [overview], [facts] or [linkGroups]. */
    val hasMore: Boolean
        get() = overview != null || facts.isNotEmpty() || linkGroups.isNotEmpty()
}

data class DetailLinkGroup(val title: String, val links: List<DetailLink>)

/** A link to follow with [DetailStore.openLink]: a genre opens Discover, a person Search, a web page the browser. */
data class DetailLink(val id: String, val label: String)

/**
 * One score of the ratings row: [logo] and [text], drawn plain (no chip behind them).
 *
 * @param source where the score is from; `detail.rating.<key>` names its item for tests.
 * @param logo the source's mark, drawn at the score's text height in its own aspect.
 * @param text the score as its site writes it: `88%`, `8.2`, `8.42`, `4.6`, `82`.
 * @param label the item's accessibility label, which names the source: `Rotten Tomatoes critics 88 percent`,
 *   `Metacritic 82`, `IMDb 8.2`.
 * @param linkId what [DetailStore.openLink] takes to open the score's page; only IMDb's has one (the addon's IMDb
 *   link), the other items open nothing.
 */
data class DetailRating(
    val source: RatingSource,
    val logo: RatingLogo,
    val text: String,
    val label: String,
    val linkId: String?,
)

/**
 * One line of the title's information: "Released" / "May 6, 2019", "Cast" / "Jared Harris, Jessie Buckley".
 *
 * @param linked the names are a [DetailHeader.linkGroups] group too (cast, directors, writers, genres): a layout that
 *   shows that group as links leaves this plain line out of its information block.
 */
data class DetailFact(val label: String, val value: String, val linked: Boolean)

/** A trailer, for [DetailStore.openTrailer]. */
data class DetailTrailer(val id: String, val label: String)

/**
 * @param label what the button reads: "Play"; an episode's code alone, "S1E4"; or for a resume only where it resumes
 *   from, "12:34" or "S1E3 · 12:34" (the play symbol, and the watched part filled inside the button, say what it does).
 * @param spokenLabel what screen readers say for it, which can't see the symbol or the fill: "Play S1E4", "Resume S1E3 ·
 *   12:34"; [label] otherwise.
 * @param resume the action resumes [videoId] from its position: with the stream it last played from as its addon offers
 *   it now, when it still does ([DetailHost.play]), else from the stream list (with auto-play on, as auto-play picks).
 * @param episodeTitle the line under the button: the episode's own title alone, "Open Wide, O Earth" ([label] has its
 *   code); null for an episode without a title of its own and for a movie.
 * @param progress how far [videoId] is, 0 to 1, when resuming: a fill inside the button.
 * @param busy the stream to play is being found: auto-play's pick, or with auto-play off the stream [videoId] last
 *   played from, asked of its addon again ([label] says so: "Finding a stream…"); pressing it again does nothing
 *   meanwhile.
 * @param noStreams "No streams found" once the stream addons, asked for [videoId] as the page showed it, have all
 *   answered and none offered a stream; a failed addon counts as none, as in the stream list (which says the same over
 *   its error). Null while any is still answering (so a late answer never has to take it back), when one offered a
 *   stream (filtered out or not), and when the device has [videoId] downloaded. Pressing the action still opens the
 *   stream list. Drawn on the TVs' pages (Android TV, Apple TV): [tvLabel], [tvSpokenLabel].
 */
data class DetailPrimaryAction(
    val label: String,
    val videoId: String,
    val resume: Boolean,
    val episodeTitle: String?,
    val progress: Double?,
    val busy: Boolean = false,
    val spokenLabel: String = label,
    val noStreams: String? = null,
) {
    /** What the button reads on the TVs' pages: [noStreams], else [label]. */
    val tvLabel: String
        get() = noStreams ?: label

    /** What screen readers say for it on the TVs' pages: [noStreams], else [spokenLabel]. */
    val tvSpokenLabel: String
        get() = noStreams ?: spokenLabel
}

/**
 * @param watched every episode of it out so far is marked watched.
 * @param menu the chip's menu, which [DetailStore.seasonMenuAction] carries out: the season's mark ("Mark season as
 *   watched"), then the whole title's ([DetailUiState.watchedActionText]).
 * @param stateText what screen readers say after its name: "Watched" while [watched] (its chip's check); null otherwise.
 */
data class DetailSeason(
    val season: Int,
    val label: String,
    val selected: Boolean,
    val watched: Boolean,
    val menu: List<MediaMenuEntry> = emptyList(),
    val stateText: String? = null,
)

/**
 * One episode (or video).
 *
 * @param code `S1E3`; null for a video that isn't an episode.
 * @param number the episode's number, `3`; null for a video that isn't an episode.
 * @param title the episode's title, else `Episode 3`.
 * @param numberedTitle `3. Open Wide, O Earth`; [title] alone for a video that isn't an episode.
 * @param moreLabel the name of its menu's button: "More actions for 3. Open Wide, O Earth".
 * @param infoLine the release date and, for an episode, the series' runtime: `May 6, 2019 · 1 h 5 min`, or
 *   `Airs May 6, 2027` before it's out; null when the addon gives neither. "Finding a stream…" while auto-play finds
 *   the stream it plays ([DetailStore.openEpisode]).
 * @param progress the resume position, 0 to 1, when the library points at this video; else null.
 * @param blurred its [thumbnail] is shown blurred past recognition, so it gives nothing away: the profile blurs
 *   unwatched episodes and this one is neither watched nor started ([progress] null), upcoming ones included. Its
 *   [overview] is blurred then too ([hiddenOverviewLabel]); its title stays.
 * @param detailLine the TV page's line for it over its row: [infoLine] and how much of it is left where it resumes,
 *   `May 6, 2019 · 1 h 5 min · 32 min left`; null with neither.
 * @param stateText what screen readers say after its name: its progress and "Watched", `45%, Watched`
 *   ([itemStateText][com.edde746.tortuga.presentation.board.itemStateText]); null with neither.
 * @param watched marked watched (Stremio's per-video marks).
 * @param upcoming not out yet.
 * @param nextUp the video [DetailUiState.primaryAction] plays.
 * @param download its download; null where downloads aren't offered (as [DetailUiState.download]) and for an episode
 *   not out yet.
 * @param menu the episode's menu (long press, right click, its ⋮), which [DetailStore.episodeMenuAction] carries out:
 *   Play from the beginning while in progress, its marks and its season's, Choose a stream with auto-play on, its
 *   download's entries, its first trailer ([mediaMenu][com.edde746.tortuga.presentation.menu.mediaMenu]).
 * @param hiddenOverviewLabel while [blurred], its [overview] is shown blurred past reading, in its place and as many
 *   lines tall (nothing moves when the setting changes), and screen readers say this instead of the text:
 *   "Description hidden"; null while it shows, and for an episode without one.
 */
data class DetailEpisode(
    val videoId: String,
    val code: String?,
    val number: String?,
    val title: String,
    val numberedTitle: String,
    val moreLabel: String,
    val infoLine: String?,
    val overview: String?,
    val thumbnail: String?,
    val progress: Double?,
    val blurred: Boolean,
    val watched: Boolean,
    val upcoming: Boolean,
    val nextUp: Boolean,
    val watchedActionText: String,
    val detailLine: String?,
    val stateText: String?,
    val download: DetailEpisodeDownload? = null,
    val menu: List<MediaMenuEntry> = emptyList(),
    val hiddenOverviewLabel: String? = null,
)

/**
 * Where a title's or an episode's download stands, in order of precedence for a title: what is downloading shows
 * before what waits, what is paused, what failed, a series partly downloaded, and a download complete.
 */
enum class DetailDownloadState {
    /** Nothing downloaded (or a download was cancelled): it can be. */
    AVAILABLE,

    /** Waiting for its turn, or for Wi-Fi. */
    QUEUED,
    DOWNLOADING,
    PAUSED,
    FAILED,

    /** Some of a series' episodes out so far are downloaded. */
    PARTIAL,
    DOWNLOADED,
}

/**
 * Detail's download button, in the action row.
 *
 * @param label "Download", "Queued", "Waiting for Wi-Fi", "Downloading 45%", "Paused", "Download failed",
 *   "3 of 10 downloaded", "Downloaded".
 * @param progress how far it is, 0 to 1, while [DetailDownloadState.DOWNLOADING] with a known size: a ring.
 * @param opensMenu a press opens [menu]: a series' "Download…" choices and Keep synced, or what can be done with a
 *   download (pause, cancel, delete). Otherwise a press acts at once ([DetailStore.pressDownload]): a movie's Download
 *   opens the Streams sheet to pick the stream to download, Paused resumes, Download failed retries.
 * @param menuTitle the menu's title: "Download".
 * @param menu the menu's groups, in order (also for a long press or the Menu key when [opensMenu] is false); each
 *   entry's id is what [DetailStore.downloadAction] takes.
 */
data class DetailDownload(
    val state: DetailDownloadState,
    val label: String,
    val progress: Double?,
    val opensMenu: Boolean,
    val menuTitle: String,
    val menu: List<DetailDownloadSection>,
)

/** A group of a download menu: [title] ("Season 2", "All seasons", "Keep synced", "Manage downloads"), or none. */
data class DetailDownloadSection(val title: String?, val entries: List<DetailDownloadEntry>)

/**
 * One choice of a download menu: "Unwatched episodes", "Keep 5 unwatched episodes", "Delete download".
 *
 * @param id what [DetailStore.downloadAction] takes.
 * @param selected the choice in effect (Keep synced's count): a check mark.
 * @param destructive it deletes downloads.
 */
data class DetailDownloadEntry(val id: String, val label: String, val selected: Boolean = false, val destructive: Boolean = false)

/**
 * An episode's download, for its row (its menu's download entries are in [DetailEpisode.menu]).
 *
 * @param state never [DetailDownloadState.PARTIAL].
 * @param label "Downloaded", "45%", "Queued", "Waiting for Wi-Fi", "Paused", "Download failed"; null while it isn't
 *   downloaded.
 * @param progress how far it is, 0 to 1, while downloading or paused with a known size.
 */
data class DetailEpisodeDownload(
    val state: DetailDownloadState,
    val label: String?,
    val progress: Double?,
)

/** What the Detail screen asks of the app around it; called on the main thread. */
interface DetailHost : MediaMenuHost {
    /**
     * Open the Streams sheet for [videoId] of [meta] over Detail (`AppGraph.streamsStore`), with [notice] on top when it
     * opens because auto-play found nothing to play; with [fromStart] (Play from the beginning) its pick plays from 0.
     */
    fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean = false)

    /** Open the Streams sheet for [videoId] of [meta] in download mode: the pick is downloaded. */
    fun openDownloadStreams(meta: MetaItem, videoId: String)

    /** Open the Keep synced screen ([com.edde746.tortuga.presentation.navigation.Route.KeepSynced]). */
    fun openKeepSynced()

    /** Open the player for [request]: resuming from the stream last played, auto-play's pick, or a download. */
    fun play(request: PlayRequest)

    /** Open [url] outside the app: the browser, or the app that handles it (YouTube, IMDb). */
    fun openUrl(url: String)

    /** Follow a `stremio:` link inside the app: a genre to Discover, a person to Search, another title to Detail. */
    fun openDeepLink(link: DeepLink)

    /**
     * Show the title [id] of [type] in place of the Detail screen of the anime list entry [entryId] (the title the
     * entry is), on [shownVideoId]'s season with the episodes brought to it, nothing opened, its header shown from
     * [preview] (the title's own, as found) while it loads; nothing when that screen is no longer the top one.
     */
    fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?)

    /** Ask a parent's PIN for [check] on this kids profile, then run [onUnlocked]; nothing runs when it is cancelled. */
    fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit)
}
