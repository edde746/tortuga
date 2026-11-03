package com.edde746.tortuga.presentation.detail

/**
 * A round button of Detail's action row, after Play (the primary action, always shown); entries in the row's order.
 * [WATCHLIST], the title on the profile's tracker lists ([DetailUiState.watchlist], read [DetailWatchlist.label]: "Add
 * to Trakt watchlist", "Tracker watchlists"), toggles the one list or opens the sheet of several. [RATE], the user's own
 * rating ([DetailUiState.userRating], read [DetailUserRating.label]: "Rate this title", "Your rating: 8"), opens the
 * rating picker where a connected tracker rates the title. [KIDS_BLOCK], "Block on this profile" on a kids profile
 * ([DetailUiState.kidsBlockAction]), is a parent's: it comes last. Where the row collapses actions behind ⋯ these three
 * always go there ([fitActions]).
 */
enum class DetailAction { CHOOSE_STREAM, LIBRARY, WATCHED, DOWNLOAD, TRAILER, SHARE, WATCHLIST, RATE, KIDS_BLOCK }

/**
 * What Detail's action row shows beside Play ([DetailUiState.fitActions]): [shown] as round buttons, then, when not every
 * action has room, a round ⋯ button opening [more]; both in the row's order.
 */
data class DetailActionFit(val shown: List<DetailAction>, val more: List<DetailAction>)

/**
 * The action row's round buttons this title has, in the row's order: Choose a stream, the download, the trailer, Share,
 * the watchlists, the rating and Block on this profile where they apply. Phones, tablets, desktop, iPad and Mac show
 * them in this order through [fitActions].
 */
val DetailUiState.actions: List<DetailAction>
    get() = DetailAction.entries.filter { action ->
        when (action) {
            DetailAction.CHOOSE_STREAM -> chooseStreamText != null
            DetailAction.LIBRARY, DetailAction.WATCHED -> true
            DetailAction.DOWNLOAD -> download != null
            DetailAction.TRAILER -> trailers.isNotEmpty()
            DetailAction.SHARE -> share != null
            DetailAction.WATCHLIST -> watchlist != null
            DetailAction.RATE -> userRating != null
            DetailAction.KIDS_BLOCK -> kidsBlockAction != null
        }
    }

/**
 * The TV's action row (Android TV, Apple TV): every round button this title has ([actions]), none behind ⋯ (a TV's row
 * is the page's width), in [TV_ORDER]. TVs offer no Share ([DetailUiState.share] is null there).
 *
 * Two orders on purpose: a remote walks a TV's single row from Play one press at a time, so the TV puts what is chosen
 * before playing (the stream list, the trailer) next to Play; touch and a pointer reach any button at once, and a
 * narrow row collapses the rest behind ⋯ by importance ([fitActions]), so phones, tablets, desktop, iPad and Mac keep
 * [actions]' order.
 */
val DetailUiState.tvActions: List<DetailAction>
    get() = actions.let { all -> TV_ORDER.filter { it in all } }

/**
 * The TV row's order: the stream list first (auto-play on), then the trailer, watched once before deciding; the
 * watchlists, the rating and a parent's Block last.
 */
private val TV_ORDER = listOf(
    DetailAction.CHOOSE_STREAM,
    DetailAction.TRAILER,
    DetailAction.LIBRARY,
    DetailAction.WATCHED,
    DetailAction.DOWNLOAD,
    DetailAction.WATCHLIST,
    DetailAction.RATE,
    DetailAction.KIDS_BLOCK,
)

/**
 * The action row with room for [slots] round buttons beside Play, each at its full size: every action when they all
 * have room; else the ⋯ button takes the last slot, the most important actions ([IMPORTANCE]) the others, and the rest
 * go behind ⋯. The watchlists ([DetailAction.WATCHLIST]), the rating ([DetailAction.RATE]) and Block on this profile
 * ([DetailAction.KIDS_BLOCK]) have no button of their own here: they are always behind ⋯, which then takes a slot
 * however much room the others have. With room for none ([slots] below 1) the row is Play and ⋯ alone. Every UI
 * measures its own [slots] and collapses the same actions.
 */
fun DetailUiState.fitActions(slots: Int): DetailActionFit {
    val all = actions
    val buttons = all - MENU_ONLY
    if (buttons.size == all.size && all.size <= slots) return DetailActionFit(all, emptyList())
    val kept = IMPORTANCE.filter { it in buttons }.take((slots - 1).coerceAtLeast(0))
    return DetailActionFit(all.filter { it in kept }, all.filterNot { it in kept })
}

/** The actions [fitActions] always puts behind ⋯. */
private val MENU_ONLY = setOf(DetailAction.WATCHLIST, DetailAction.RATE, DetailAction.KIDS_BLOCK)

/**
 * The actions a narrow row keeps, most important first:
 * - Choose a stream: with auto-play on, the one way to pick the stream Play's video plays (a movie has no episode menu);
 * - the library: every title has it, and it decides what Home and the Library list;
 * - the download: how a title goes offline, and the menu of a series' season downloads and Keep synced; behind ⋯ its
 *   state still shows in the line under the row;
 * - watched: playback marks it by itself, and a series' episodes and seasons have it in their menus;
 * - the trailer: watched once, before deciding to watch the title;
 * - Share: sending the title to someone else.
 */
private val IMPORTANCE = listOf(
    DetailAction.CHOOSE_STREAM,
    DetailAction.LIBRARY,
    DetailAction.DOWNLOAD,
    DetailAction.WATCHED,
    DetailAction.TRAILER,
    DetailAction.SHARE,
)
