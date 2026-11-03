package com.edde746.tortuga.presentation.navigation

import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.strings.T

/**
 * The screens of a running app (one profile), as back-stack entries ([TortugaNavigator]). Plain values: a screen's
 * store is built from them (and the app state), never carried in them, so a route can be compared, logged and
 * restored. Sign-in and the profile picker come before a profile runs, outside the back stack (the
 * account gate decides them).
 *
 * A route is drawn as a screen of its own or as a sheet over the entry below it ([presentation]; [BackStackLayout]
 * tells the UIs which entry is which).
 */
sealed interface Route {
    /** How the UIs present this route when it is the top entry with another entry below it. */
    val presentation: RoutePresentation get() = RoutePresentation.SCREEN

    /** The first page of every catalog without required extras. */
    data object Board : Route

    /** Catalogs with type, catalog and genre filters. */
    data object Discover : Route

    /** Catalogs with the `search` extra. */
    data object Search : Route

    /**
     * A meta item's page (seasons and episodes for a series). With a [videoId] that video's season is shown and, once
     * the item is known, [videoAction] is done with it. With a [shownVideoId] (an anime list entry opened as its title:
     * a sequel's first episode, a second cour's) that video's season is shown and the episodes are brought to it,
     * nothing opened.
     */
    data class Detail(
        val type: String,
        val id: String,
        val videoId: String? = null,
        val shownVideoId: String? = null,
        val videoAction: DetailVideoAction = DetailVideoAction.STREAMS,
    ) : Route

    /**
     * The streams of [videoId] of the item [type]/[id], grouped by addon (a movie's video id is its id): a sheet over
     * the screen that opened it (Detail, or the player asking for the next episode's stream). Playing a stream replaces
     * it with the player ([TortugaNavigator.navigate]), so Back from the player returns to the screen under the sheet.
     * With [download] the list is for downloading: a pick queues the stream's download and closes the sheet. With
     * [fromStart] (Play from the beginning) a pick plays from 0, not from the library's position.
     */
    data class Streams(val type: String, val id: String, val videoId: String, val download: Boolean = false, val fromStart: Boolean = false) : Route {
        override val presentation: RoutePresentation get() = RoutePresentation.SHEET
    }

    /** The player, over the app's current playback. */
    data object Player : Route

    /** The library, Continue Watching and, where this device has downloads, Downloads ([LibrarySection]). */
    data object Library : Route

    /** Settings: account, devices, profiles, playback, addons. */
    data object Settings : Route

    /**
     * Watch together (the lobby): join a room by code or invite link, recent rooms, the relay, and the room once in one.
     * Pushed over Home ([com.edde746.tortuga.app.AppSession.openWatchTogether]: Home's Watch together shortcut, an
     * invite link). A room starts in the player.
     */
    data object WatchTogether : Route

    /**
     * Keep synced: the profile's rules, each series kept at its count of unwatched episodes downloaded, to pause or stop.
     * Pushed over Downloads or Detail, where this device has downloads.
     */
    data object KeepSynced : Route

    /**
     * The TV guide: the guide catalogs' channels and programmes by day. Pushed over Home from its TV guide shortcut.
     */
    data object Guide : Route

    /**
     * The calendar of the library's series' episodes, pushed over Home or Library
     * ([com.edde746.tortuga.app.AppSession.openCalendar]: Home's Calendar shortcut and Library's Calendar button).
     */
    data object Calendar : Route

    /**
     * The folder [folderId] of the profile's collection [collectionId]: its sources' titles, pushed over Home from its
     * tile ([com.edde746.tortuga.presentation.collections.FolderStore]).
     */
    data class CollectionFolder(val collectionId: String, val folderId: String) : Route
}

/**
 * What the Detail screen does once the item is known: with the video it is opened on ([Route.Detail.videoId]), or, for
 * a card's menu entry carried out on Detail ([com.edde746.tortuga.presentation.menu.MediaAction.onDetail]), on the
 * video Detail's Play plays when the card names none.
 */
enum class DetailVideoAction {
    /**
     * Opens its stream list, as Stremio's detail route does: a `stremio:///detail/…/{videoId}` link, a live item's
     * card, a card whose default video is another one.
     */
    STREAMS,

    /**
     * Plays it as Detail's Play, Resume and episodes play a video (`DetailStore.playStreamed`), from where the library
     * has it: a Continue Watching item.
     */
    PLAY,

    /**
     * A `stremio:///detail/…/{videoId}?autoPlay=true` link, a channel the TV guide plays: plays it as [PLAY] does,
     * except that with the profile's auto-play off it plays the stream it last played from, else the first that passes
     * the filters.
     */
    AUTO_PLAY_LINK,

    /** Plays the video from 0 as [PLAY] plays it (the menu's Play from the beginning). */
    PLAY_FROM_START,

    /** Opens the video's stream list, whatever auto-play would pick. */
    CHOOSE_STREAM,

    /** Opens the video's stream list to download from. */
    DOWNLOAD,

    /** Plays the title's first trailer. */
    TRAILER,
}

/** How a route is drawn ([Route.presentation]). */
enum class RoutePresentation {
    /** A screen of its own, in the navigation container. */
    SCREEN,

    /**
     * A sheet over the entry below it (Compose `TortugaSheet`: a bottom sheet, a side panel on TV; Apple `.sheet`),
     * which stays drawn underneath. Back closes it.
     */
    SHEET,
}

/**
 * A back stack as the UIs draw it: [screens] in the navigation container (bottom to top, the last one on screen) and
 * the [sheet] over the last of them, if any. Only the top entry can be a sheet, and only with an entry below it: a
 * sheet route alone on the stack (a development session opened on Streams) is drawn as a screen.
 */
data class BackStackLayout(val screens: List<Route>, val sheet: Route?) {
    companion object {
        fun of(backStack: List<Route>): BackStackLayout {
            val top = backStack.lastOrNull()
            return if (top != null && backStack.size > 1 && top.presentation == RoutePresentation.SHEET) {
                BackStackLayout(backStack.dropLast(1), top)
            } else {
                BackStackLayout(backStack, null)
            }
        }
    }
}

/**
 * A top-level destination of the navigation bar, rail, sidebar or TV tab bar. Each UI picks its own icon. Addons is
 * not one: it is a section of Settings ([SettingsSection.ADDONS][com.edde746.tortuga.presentation.settings.SettingsSection.ADDONS]).
 */
enum class TopLevelDestination(val route: Route) {
    BOARD(Route.Board),
    DISCOVER(Route.Discover),
    SEARCH(Route.Search),
    LIBRARY(Route.Library),
    SETTINGS(Route.Settings),
    ;

    /**
     * The destination's name where it has the room it needs: the TV column, screen titles, iPhone's tab bar, tvOS, and
     * iPad's and the Mac's sidebars.
     */
    val label: String
        get() = when (this) {
            BOARD -> T.nav.destination.board
            DISCOVER -> T.nav.destination.discover
            SEARCH -> T.nav.destination.search
            LIBRARY -> T.nav.destination.library
            SETTINGS -> T.nav.destination.settings
        }

    /**
     * The destination's name where it has little room: Compose's bar (a 360 dp phone's) and rail, 72 dp a slot, and
     * iPad's floating tab bar. A shorter word in the languages whose [label] is wider than that (de Optionen for
     * Einstellungen), else the [label] itself. Discover has no short key and always shows its [label].
     */
    val shortLabel: String
        get() = when (this) {
            BOARD -> T.nav.shortLabel.board
            DISCOVER -> T.nav.destination.discover
            SEARCH -> T.nav.shortLabel.search
            LIBRARY -> T.nav.shortLabel.library
            SETTINGS -> T.nav.shortLabel.settings
        }

    companion object {
        /** The destination [route] is, or null for a screen below the top level. */
        fun of(route: Route): TopLevelDestination? = entries.firstOrNull { it.route == route }

        /**
         * The destination the chrome selects for [backStack], a destination's stack: the one at its bottom; null for a
         * stack without one (a development session started on another screen), which shows without the chrome.
         */
        fun selectedIn(backStack: List<Route>): TopLevelDestination? = backStack.firstOrNull()?.let(::of)
    }
}

/**
 * A section of Library ([Route.Library]), each a tab of the screen: the library, Continue Watching and, where this
 * device has downloads ([com.edde746.tortuga.app.AppSession.librarySections]), Downloads. Library opens on Downloads
 * while offline and when asked to ([TortugaNavigator.openLibrary]).
 */
enum class LibrarySection {
    LIBRARY,
    CONTINUE_WATCHING,
    DOWNLOADS,
    ;

    val label: String
        get() = when (this) {
            LIBRARY -> T.library.title
            CONTINUE_WATCHING -> T.library.continueWatching
            DOWNLOADS -> T.downloads.title
        }
}
