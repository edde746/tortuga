package com.edde746.tortuga.report

import com.edde746.tortuga.app.AppOverlay
import com.edde746.tortuga.app.RootScreen
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.app.SettingsSession
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.presentation.account.AccountDestination
import com.edde746.tortuga.presentation.account.DeviceLinkUiState
import com.edde746.tortuga.presentation.account.IdentityLinkOptions
import com.edde746.tortuga.presentation.account.IdentityLinkStep
import com.edde746.tortuga.presentation.account.IdentityLinkStore
import com.edde746.tortuga.presentation.account.IdentityLinkUiState
import com.edde746.tortuga.presentation.account.SignInStep
import com.edde746.tortuga.presentation.account.SignInStore
import com.edde746.tortuga.presentation.account.SignInUiState
import com.edde746.tortuga.presentation.addons.AddonBrowseBody
import com.edde746.tortuga.presentation.addons.AddonInstallAction
import com.edde746.tortuga.presentation.addons.AddonInstallStep
import com.edde746.tortuga.presentation.addons.AddonPreviewUiState
import com.edde746.tortuga.presentation.addons.AddonRow
import com.edde746.tortuga.presentation.addons.AddonsBody
import com.edde746.tortuga.presentation.addons.AddonsUiState
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.board.BoardUiState
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.calendar.CalendarBody
import com.edde746.tortuga.presentation.calendar.CalendarStore
import com.edde746.tortuga.presentation.calendar.CalendarUiState
import com.edde746.tortuga.presentation.collections.CollectionEditorUiState
import com.edde746.tortuga.presentation.collections.CollectionImportStep
import com.edde746.tortuga.presentation.collections.CollectionImportUiState
import com.edde746.tortuga.presentation.collections.FolderNote
import com.edde746.tortuga.presentation.collections.FolderStore
import com.edde746.tortuga.presentation.collections.FolderUiState
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailStore
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.discover.DiscoverMore
import com.edde746.tortuga.presentation.discover.DiscoverStore
import com.edde746.tortuga.presentation.discover.DiscoverUiState
import com.edde746.tortuga.presentation.downloads.DownloadRow
import com.edde746.tortuga.presentation.downloads.DownloadsBody
import com.edde746.tortuga.presentation.downloads.DownloadsStore
import com.edde746.tortuga.presentation.downloads.DownloadsUiState
import com.edde746.tortuga.presentation.downloads.KeepSyncedBody
import com.edde746.tortuga.presentation.downloads.KeepSyncedStore
import com.edde746.tortuga.presentation.downloads.KeepSyncedUiState
import com.edde746.tortuga.presentation.guide.GuideBody
import com.edde746.tortuga.presentation.guide.GuideStore
import com.edde746.tortuga.presentation.guide.GuideUiState
import com.edde746.tortuga.presentation.library.LibraryBody
import com.edde746.tortuga.presentation.library.LibraryStore
import com.edde746.tortuga.presentation.library.LibraryUiState
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.TortugaNavigator
import com.edde746.tortuga.presentation.player.NowPlaying
import com.edde746.tortuga.presentation.player.PlaybackMode
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.TimelineState
import com.edde746.tortuga.presentation.player.TrackItem
import com.edde746.tortuga.presentation.profiles.ProfileEditorStore
import com.edde746.tortuga.presentation.profiles.ProfileEditorUiState
import com.edde746.tortuga.presentation.profiles.ProfilePickerBody
import com.edde746.tortuga.presentation.profiles.ProfilePickerStore
import com.edde746.tortuga.presentation.profiles.ProfilePickerUiState
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.presentation.search.SearchGroup
import com.edde746.tortuga.presentation.search.SearchGroupStatus
import com.edde746.tortuga.presentation.search.SearchStore
import com.edde746.tortuga.presentation.search.SearchUiState
import com.edde746.tortuga.presentation.settings.AccountSettingsUiState
import com.edde746.tortuga.presentation.settings.AppearanceSettingsUiState
import com.edde746.tortuga.presentation.settings.DataSettingsUiState
import com.edde746.tortuga.presentation.settings.DeviceSettingsUiState
import com.edde746.tortuga.presentation.settings.DownloadSettingsUiState
import com.edde746.tortuga.presentation.settings.HomeSettingsBody
import com.edde746.tortuga.presentation.settings.HomeSettingsUiState
import com.edde746.tortuga.presentation.settings.LanguagePickerUiState
import com.edde746.tortuga.presentation.settings.LinkApprovalStep
import com.edde746.tortuga.presentation.settings.PlaybackNumber
import com.edde746.tortuga.presentation.settings.PlaybackSettingRow
import com.edde746.tortuga.presentation.settings.PlaybackSettingsUiState
import com.edde746.tortuga.presentation.settings.PlayerChoiceRow
import com.edde746.tortuga.presentation.settings.PlayerSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingStepperRow
import com.edde746.tortuga.presentation.settings.SettingsUiState
import com.edde746.tortuga.presentation.settings.StreamChoicesKind
import com.edde746.tortuga.presentation.settings.StreamPatternKind
import com.edde746.tortuga.presentation.settings.TrackerConnectStep
import com.edde746.tortuga.presentation.settings.TrackersSettingsUiState
import com.edde746.tortuga.presentation.streams.GroupStatus
import com.edde746.tortuga.presentation.streams.StreamsStore
import com.edde746.tortuga.presentation.streams.StreamsUiState
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.presentation.watch.WatchLobbyUiState
import com.edde746.tortuga.presentation.watch.WatchRoomUi
import com.edde746.tortuga.presentation.watch.WatchStore
import com.edde746.tortuga.sync.engine.SyncStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The test report's fields, which the test harnesses read: what Android's debug and harness builds (logcat lines) and
 * the desktop harness (report.json samples) write for each screen's state, the player
 * engine's state, its tracks and the device's downloads, so both apps report one name and one shape for each.
 * Each platform keeps its own envelope and puts these objects in it under the key each function names ([screen]: the
 * shown screen's, from its stores; [name]: the screens' names). [listLimit] cuts the screens' lists, the player's menus
 * and the chapters (Android's logcat entries stay under 4 KB); the engine's tracks and the downloads stay whole. A null
 * value is written as JSON null. Stream URLs, addon links and tokens never appear: streams by name, an addon's link only
 * as whether it shows; link and device codes as the screen shows them (a harness types them on another device).
 */
class Report(private val listLimit: Int = Int.MAX_VALUE) {
    /** `board`, `detail`, `player`: [route]'s name in `screen`, `backStack` and `sheet` (spelled out: R8 renames classes). */
    fun name(route: Route): String = when (route) {
        Route.Board -> "board"
        Route.Discover -> "discover"
        Route.Search -> "search"
        is Route.Detail -> "detail"
        is Route.Streams -> "streams"
        Route.Player -> "player"
        Route.Library -> "library"
        Route.Settings -> "settings"
        Route.WatchTogether -> "watch"
        Route.KeepSynced -> "keepSynced"
        Route.Guide -> "guide"
        Route.Calendar -> "calendar"
        is Route.CollectionFolder -> "folder"
    }

    /** `starting`, `signin`, `profiles`, `main`: the account shell's screen, the `screen` while no route is on top. */
    fun name(screen: RootScreen): String = when (screen) {
        RootScreen.Starting -> "starting"
        RootScreen.SignIn -> "signin"
        RootScreen.Profiles -> "profiles"
        is RootScreen.Main -> "main"
    }

    /**
     * The fields of [route]'s screen from the stores [navigator] keeps for it, under the keys of the functions below:
     * `board`, `discover`, `search`, `detail`, `streams`, `library` with `continueWatching` and `downloadsSection`,
     * `settings` with `addons`, `watch`, `keepSynced`, `guide`, `calendar` and `folder`. A store not kept (yet) adds
     * nothing.
     */
    fun screen(navigator: TortugaNavigator, route: Route): Map<String, JsonElement> = buildMap {
        fun kept(key: String) = navigator.retained(route, key)
        when (route) {
            Route.Board -> (kept(BoardStore.KEY) as? BoardStore)?.let { put("board", board(it.state.value)) }
            Route.Discover -> (kept(DiscoverStore.KEY) as? DiscoverStore)?.let { put("discover", discover(it.state.value)) }
            Route.Search -> (kept(SearchStore.KEY) as? SearchStore)?.let { put("search", search(it.state.value, it.recent)) }
            is Route.Detail -> (kept(DetailStore.KEY) as? DetailStore)?.let { put("detail", detail(it.state.value)) }
            is Route.Streams -> (kept(StreamsStore.KEY) as? StreamsStore)?.let { put("streams", streams(it.state.value)) }
            Route.Library -> {
                (kept(LibraryStore.KEY) as? LibraryStore)?.let { put("library", library(it.state.value)) }
                (kept(LibraryStore.CONTINUE_WATCHING_KEY) as? LibraryStore)?.let { put("continueWatching", library(it.state.value)) }
                (kept(DownloadsStore.KEY) as? DownloadsStore)?.let { put("downloadsSection", downloadsSection(it.state.value)) }
            }
            Route.Settings -> (kept(SettingsSession.KEY) as? SettingsSession)?.let { s ->
                put(
                    "settings",
                    settings(
                        s.main.state.value, s.deviceStore?.state?.value, s.accountStore?.state?.value, s.playbackStore?.state?.value,
                        s.playerStore?.state?.value, s.trackersStore?.state?.value, s.homeStore?.state?.value,
                        s.downloadsStore?.state?.value, s.dataStore?.state?.value, s.appearanceStore?.state?.value,
                        s.homeStore?.collectionImport?.state?.value, s.homeStore?.collectionEditor?.state?.value,
                    ),
                )
                s.addonsStore?.let { put("addons", addons(it.state.value)) }
            }
            Route.WatchTogether -> (kept(WatchStore.KEY) as? WatchStore)?.let { put("watch", watch(it.state.value)) }
            Route.KeepSynced -> (kept(KeepSyncedStore.KEY) as? KeepSyncedStore)?.let { put("keepSynced", keepSynced(it.state.value)) }
            Route.Guide -> (kept(GuideStore.KEY) as? GuideStore)?.let { put("guide", guide(it.state.value)) }
            Route.Calendar -> (kept(CalendarStore.KEY) as? CalendarStore)?.let { put("calendar", calendar(it.state.value)) }
            is Route.CollectionFolder -> (kept(FolderStore.KEY) as? FolderStore)?.let { put("folder", folder(it.state.value)) }
            else -> {}
        }
    }

    /**
     * `board`: Continue Watching (each card's new episodes), the shortcut chips, each row's count, first title and
     * cards' ids, and the offline state.
     */
    fun board(s: BoardUiState): JsonObject = obj(
        "loading" to (s.body == BoardBody.Loading),
        "continueWatching" to s.continueWatching.cut().map {
            obj(
                "id" to it.id, "videoId" to it.videoId, "title" to it.title, "detail" to it.detail, "progress" to it.progress,
                "newEpisodes" to it.newEpisodes,
            )
        },
        "shortcuts" to s.shortcuts.cut().map { obj("key" to it.key, "label" to it.label) },
        "rows" to (s.body as? BoardBody.Rows)?.rows.orEmpty().cut().map { r ->
            val status = r.status
            obj(
                "title" to r.title, "source" to r.sourceName, "fromTracker" to r.fromTracker, "status" to status.label(),
                "error" to (status as? RowStatus.Failed)?.text, "items" to r.items.size, "first" to r.items.firstOrNull()?.title, "seeAll" to r.seeAll.name,
                "ids" to r.items.cut().map { it.id }, "captions" to r.items.firstOrNull()?.caption,
                // A collection's folder tiles: each one's title and note.
                "folders" to r.folders.cut().map { obj("title" to it.title, "note" to it.note) },
            )
        },
        // Its title and action ("Go to Downloads", null without downloads).
        "offline" to (s.body as? BoardBody.Offline)?.offline?.let { obj("title" to it.title, "text" to it.text, "actionText" to it.actionText) },
    )

    /**
     * `folder`: a collection's folder, its title and layout, the tabs by title and the selected one, the grid's status,
     * count and first title ([FolderUiState.grid]) and each row's ([FolderUiState.rows]), and each note's text.
     */
    fun folder(s: FolderUiState): JsonObject = obj(
        "title" to s.title,
        "layout" to s.layout.name,
        "tabs" to s.tabs.cut().map { it.title },
        "selectedTab" to s.selectedTab,
        "grid" to s.grid?.let { g ->
            obj("status" to g.status.label(), "items" to g.items.size, "first" to g.items.firstOrNull()?.title, "note" to g.note?.let(::folderNote))
        },
        "rows" to s.rows.cut().map { r ->
            obj(
                "title" to r.title, "source" to r.sourceName, "status" to r.status.label(), "items" to r.items.size,
                "first" to r.items.firstOrNull()?.title, "note" to r.note?.let(::folderNote),
            )
        },
        "gone" to s.gone,
    )

    private fun folderNote(n: FolderNote) = obj("text" to n.text, "action" to n.action?.name)

    private fun RowStatus.label(): String = when (this) {
        RowStatus.Idle -> "IDLE"
        RowStatus.Loading -> "LOADING"
        is RowStatus.Ready -> "READY"
        is RowStatus.Failed -> "FAILED"
    }

    private fun SearchGroupStatus.label(): String = when (this) {
        SearchGroupStatus.Loading -> "LOADING"
        is SearchGroupStatus.Ready -> "READY"
        is SearchGroupStatus.Failed -> "FAILED"
    }

    /**
     * `discover`: the type, catalog (its place in the menu and its addon or tracker) and filters with every option's
     * label, how many items, the first items' ids and watched marks, and the paging.
     */
    fun discover(s: DiscoverUiState): JsonObject = discover(s, s.body as? DiscoverBody.Grid)

    private fun discover(s: DiscoverUiState, grid: DiscoverBody.Grid?): JsonObject = obj(
        "title" to s.title,
        "type" to s.types?.selection,
        "catalog" to s.catalogs?.selection,
        "catalogIndex" to s.catalogs?.options?.indexOfFirst { it.selected },
        "catalogSource" to s.catalogs?.options?.firstOrNull { it.selected }?.detail,
        "filters" to s.filters.map { f -> obj("label" to f.label, "selection" to f.selection, "options" to f.options.map { it.label }) },
        "items" to grid?.items.orEmpty().size,
        "first" to grid?.items.orEmpty().cut().map { obj("id" to it.id, "title" to it.title, "watched" to it.watched) },
        "loading" to (s.body == DiscoverBody.Loading),
        "error" to (s.body as? DiscoverBody.Failed)?.text,
        "loadingMore" to (grid?.more == DiscoverMore.Loading),
        "moreError" to (grid?.more as? DiscoverMore.Failed)?.text,
        "canLoadMore" to (grid?.more == DiscoverMore.Available),
    )

    /** `search`: the query, the recent searches ([recent], shown while it is blank), each result group with its first ids. */
    fun search(s: SearchUiState, recent: List<String>): JsonObject = search(s, recent, (s.body as? SearchBody.Results)?.groups.orEmpty())

    private fun search(s: SearchUiState, recent: List<String>, groups: List<SearchGroup>): JsonObject = obj(
        "query" to s.query,
        "loading" to s.searching,
        "showsRecent" to (s.body is SearchBody.Recent),
        "recent" to recent.cut(),
        "groups" to groups.cut().map { g ->
            obj(
                "title" to g.title, "addon" to g.addonName, "status" to g.status.label(), "error" to (g.status as? SearchGroupStatus.Failed)?.text,
                "items" to g.items.size,
                "first" to g.items.firstOrNull()?.title, "ids" to g.items.take(4).map { it.id },
            )
        },
        "items" to groups.sumOf { it.items.size },
    )

    /**
     * `detail`: the title, its ratings, Play (`primaryAction`), the library and watched marks, seasons and episodes
     * (each one's still blurred or not, and its description blurred or not), the download button with its menu (each
     * entry's id, label and whether it is selected), the notices, the question
     * before a season's or the title's downloads go (its title), a player app's hand-off, "More like this" (`related`:
     * null without the row; its titles' ids, names and marks), and on a kids profile the blocked state that replaces
     * the page (`kidsBlock`) and Block on this profile (`kidsBlockAction`).
     */
    fun detail(d: DetailUiState): JsonObject = obj(
        "loading" to (d.body == DetailBody.Loading || d.body is DetailBody.Preview),
        "errorText" to (d.body as? DetailBody.Failed)?.text,
        "kidsBlock" to (d.body as? DetailBody.Blocked)?.let { obj("title" to it.title, "reason" to it.reason, "askLabel" to it.askLabel) },
        "kidsBlockAction" to d.kidsBlockAction,
        "title" to d.body.header()?.title,
        "type" to d.body.header()?.type,
        "ratings" to d.body.header()?.ratings.orEmpty().map { obj("source" to it.source.key, "logo" to it.logo.name, "text" to it.text, "label" to it.label) },
        "primaryAction" to d.primaryAction?.let { obj("label" to it.label, "videoId" to it.videoId, "resume" to it.resume, "progress" to it.progress) },
        "inLibrary" to d.inLibrary,
        "libraryActionText" to d.libraryActionText,
        "watched" to d.watched,
        "seasons" to d.seasons.cut().map { obj("season" to it.season, "label" to it.label, "selected" to it.selected, "watched" to it.watched) },
        "episodes" to d.episodes.cut().map { e ->
            obj(
                "videoId" to e.videoId, "code" to e.code, "title" to e.title, "watched" to e.watched, "progress" to e.progress,
                "blurred" to e.blurred, "overviewHidden" to (e.hiddenOverviewLabel != null),
                "download" to e.download?.let { obj("state" to it.state.name, "label" to it.label, "progress" to it.progress) },
                "menu" to e.menu.map { it.action.name },
            )
        },
        "chooseStream" to (d.chooseStreamText != null),
        "share" to (d.share != null),
        "download" to d.download?.let { dl ->
            obj(
                "state" to dl.state.name, "label" to dl.label, "progress" to dl.progress, "opensMenu" to dl.opensMenu,
                "menu" to dl.menu.map { section ->
                    obj("title" to section.title, "entries" to section.entries.map { obj("id" to it.id, "label" to it.label, "selected" to it.selected) })
                },
            )
        },
        "downloadNotice" to d.downloadNotice,
        "downloadDelete" to d.downloadDelete?.title,
        "offlineNote" to d.offlineNote,
        "userRating" to d.userRating?.let { u ->
            obj(
                "text" to u.text, "label" to u.label, "score" to u.score,
                "trackers" to u.trackers.map { t -> obj("service" to t.service.id, "status" to t.status, "busy" to t.busy, "attention" to t.attention) },
            )
        },
        "playerApp" to d.playerApp?.let { obj("text" to it.text, "markWatched" to it.markWatchedText, "notYet" to it.notYetText) },
        "related" to d.related?.let { r ->
            obj(
                "title" to r.title, "loading" to r.loading, "emptyText" to r.emptyText,
                "items" to r.cards.cut().map { obj("id" to it.id, "title" to it.title, "watched" to it.watched, "inLibrary" to it.inLibrary) },
            )
        },
    )

    /** The header the body draws: a preview's or the loaded title's. */
    private fun DetailBody.header(): DetailHeader? = when (this) {
        is DetailBody.Page -> header
        DetailBody.Loading, is DetailBody.Failed, is DetailBody.Blocked -> null
    }

    /**
     * `streams`: the addon chips by name (the selected one marked with a trailing `*`), the sort (what the list goes by,
     * and its options by id, the ones in effect marked `*`), each group with its rows by name, how many of them offer
     * Download and its suggested row (its index among all the group's rows, past the cut too, the mark's label and
     * reason; null for none), download mode and the "Play with…" menu.
     */
    fun streams(s: StreamsUiState): JsonObject = obj(
        "title" to s.title,
        "subtitle" to s.subtitle,
        "loading" to !s.answered,
        "notice" to s.notice,
        "downloadMode" to s.downloadMode,
        "downloadMessage" to s.downloadMessage,
        "playWith" to s.playWith?.let { m -> obj("title" to m.title, "entries" to m.entries.map { obj("playerId" to it.playerId, "label" to it.label) }) },
        "filter" to s.filter?.let { obj("text" to it.text, "showing" to it.showing) },
        "addons" to s.addons.map { if (it.selected) "${it.name}*" else it.name },
        "sort" to s.sort?.let { m -> obj("label" to m.label, "sorted" to m.sorted, "options" to m.options.map { if (it.selected) "${it.id}*" else it.id }) },
        "groups" to s.groups.cut().map { g ->
            obj(
                "addon" to g.addonName,
                "status" to when (g.status) {
                    GroupStatus.Loading -> "LOADING"
                    GroupStatus.Ready -> "READY"
                    is GroupStatus.Failed -> "FAILED"
                },
                "error" to (g.status as? GroupStatus.Failed)?.error,
                "rows" to g.streams.cut().map { it.name },
                "unplayableNote" to g.unplayableNote, "filteredNote" to g.filteredNote,
                "downloadActions" to g.streams.count { it.downloadActionText != null },
                "suggested" to g.streams.withIndex().firstNotNullOfOrNull { (i, row) ->
                    row.suggestion?.let { obj("index" to i, "label" to it.label, "reason" to it.reason) }
                },
            )
        },
    )

    /** `library` (and its `continueWatching` tab): its items (each series' new episodes) and the empty text. */
    fun library(l: LibraryUiState): JsonObject = obj(
        "title" to l.title,
        "loading" to (l.body == LibraryBody.Loading),
        "items" to (l.body as? LibraryBody.Grid)?.items.orEmpty().cut().map {
            obj("id" to it.id, "type" to it.type, "name" to it.name, "watched" to it.watched, "progress" to it.progress, "newEpisodes" to it.newEpisodes)
        },
        "emptyText" to (l.body as? LibraryBody.Empty)?.text,
    )

    /**
     * `downloadsSection`, Library's Downloads section: each title's group with a movie's row or an episode's rows by
     * season (each row's status, progress, failure and actions), the storage line, Pause all and Resume all, the
     * offline note, the row to Keep synced, the empty text and the question before a group's downloads go (its title).
     */
    fun downloadsSection(s: DownloadsUiState): JsonObject = obj(
        "loading" to (s.body == DownloadsBody.Loading),
        "emptyText" to (s.body as? DownloadsBody.Empty)?.text,
        "offlineNote" to s.offlineNote,
        "storage" to s.storage,
        "pauseAllText" to s.pauseAllText,
        "resumeAllText" to s.resumeAllText,
        "keepSynced" to s.keepSynced?.value,
        "deleteDialog" to s.deleteDialog?.title,
        "groups" to (s.body as? DownloadsBody.Groups)?.groups.orEmpty().cut().map { g ->
            obj(
                "key" to g.key, "type" to g.type, "title" to g.title, "summary" to g.summary,
                "seasons" to g.seasons.map { obj("season" to it.season, "title" to it.title, "rows" to it.episodes.cut().map(::downloadRow)) },
                "movie" to g.movie?.let(::downloadRow),
            )
        },
    )

    private fun downloadRow(r: DownloadRow) = obj(
        "id" to r.id, "videoId" to r.videoId, "title" to r.title, "subtitle" to r.subtitle, "status" to r.status.name,
        "progress" to r.progress, "failureText" to r.failureText, "actions" to r.actions.map { it.action.name },
    )

    /** `keepSynced`, the Keep synced screen: each rule's series, count, state and pause, the stop question and the notice. */
    fun keepSynced(s: KeepSyncedUiState): JsonObject = obj(
        "loading" to (s.body == KeepSyncedBody.Loading),
        "emptyText" to (s.body as? KeepSyncedBody.Empty)?.text,
        "rules" to (s.body as? KeepSyncedBody.Rules)?.rules.orEmpty().cut().map {
            obj("metaId" to it.metaId, "title" to it.title, "count" to it.countText, "status" to it.statusText, "paused" to it.paused)
        },
        "stopDialog" to s.stopDialog?.let { obj("title" to it.title, "message" to it.message, "delete" to it.deleteLabel) },
        "notice" to s.notice,
    )

    /**
     * `guide`, the TV guide: its status (`LOADING`, `READY`, `FAILED`, `EMPTY`) and message, the days and catalogs with the selected ones, and each channel's
     * id, name, count of programmes, and the index and title of the one on now and of the next.
     */
    fun guide(s: GuideUiState): JsonObject = obj(
        "status" to when (s.body) {
            GuideBody.Loading -> "LOADING"
            is GuideBody.Grid -> "READY"
            is GuideBody.Failed -> "FAILED"
            is GuideBody.Empty -> "EMPTY"
        },
        "message" to when (val body = s.body) {
            GuideBody.Loading, is GuideBody.Grid -> null
            is GuideBody.Failed -> body.text
            is GuideBody.Empty -> body.text
        },
        "days" to s.days.map { it.label },
        "day" to s.days.firstOrNull { it.selected }?.label,
        "catalogs" to s.catalogs.map { it.label },
        "catalog" to s.catalogs.firstOrNull { it.selected }?.label,
        "channels" to s.channels.cut().map { c ->
            obj(
                "id" to c.id, "name" to c.name, "programmes" to c.programmes.size,
                "onNow" to c.onNow, "onNowTitle" to c.onNow?.let { c.programmes[it].title },
                "next" to c.next, "nextTitle" to c.next?.let { c.programmes[it].title },
            )
        },
        "hasMore" to s.hasMore,
    )

    /**
     * `calendar`: the month, today's day, the days with episodes (each episode's series, video id, line, whether it is
     * blurred and whether its description is), whether it is loading and the empty text, as its body says.
     */
    fun calendar(s: CalendarUiState): JsonObject = obj(
        "month" to s.month,
        "currentMonth" to s.currentMonth,
        "today" to s.days.firstOrNull { it.today }?.day,
        "loading" to (s.body == CalendarBody.Loading),
        "emptyText" to (s.body as? CalendarBody.Empty)?.text,
        "agenda" to s.agenda.cut().map { day ->
            obj(
                "day" to day.day, "label" to day.label,
                "episodes" to day.episodes.cut().map {
                    obj(
                        "metaId" to it.metaId, "videoId" to it.videoId, "series" to it.series, "detail" to it.detail, "blurred" to it.blurred,
                        "overviewHidden" to (it.hiddenOverviewLabel != null),
                    )
                },
            )
        },
    )

    /**
     * `addons`: its rows by name and those switched off, the section shown and Browse (catalogs, types and addons by
     * name), the install flow (with the watch-activity tick box, null where the preview has none, and whether the main
     * button can be pressed), the details panel (its addon, whether the link is revealed and shown, its switch and its
     * watch-activity switch, null where it has none) and the removal dialog.
     */
    fun addons(s: AddonsUiState): JsonObject {
        val step = s.install.step
        val preview = when (step) {
            is AddonInstallStep.Preview -> step.preview
            is AddonInstallStep.Installing -> step.preview
            AddonInstallStep.Closed, is AddonInstallStep.EnterLink, AddonInstallStep.Loading, is AddonInstallStep.Installed -> null
        }
        return addons(s, (s.body as? AddonsBody.Rows)?.addons.orEmpty(), step, preview)
    }

    private fun addons(s: AddonsUiState, rows: List<AddonRow>, step: AddonInstallStep, preview: AddonPreviewUiState?): JsonObject = obj(
        "loading" to (s.body == AddonsBody.Loading),
        "addons" to rows.cut().map { it.name },
        "off" to rows.filterNot { it.enabled }.map { it.name },
        "section" to s.section.name,
        "browse" to s.browse?.let { b ->
            obj(
                "catalogs" to b.catalogs.map { it.label }, "catalog" to b.catalogs.firstOrNull { it.selected }?.label,
                "types" to b.types.map { it.label }, "items" to (b.body as? AddonBrowseBody.Results)?.items.orEmpty().cut().map { it.name },
                "loading" to (b.body == AddonBrowseBody.Loading), "error" to (b.body as? AddonBrowseBody.Failed)?.text,
            )
        },
        "install" to obj(
            "step" to when (step) {
                AddonInstallStep.Closed -> "CLOSED"
                is AddonInstallStep.EnterLink -> "ENTER_LINK"
                AddonInstallStep.Loading -> "LOADING"
                is AddonInstallStep.Preview -> "PREVIEW"
                is AddonInstallStep.Installing -> "INSTALLING"
                is AddonInstallStep.Installed -> "INSTALLED"
            },
            "error" to when (step) {
                is AddonInstallStep.EnterLink -> step.error
                is AddonInstallStep.Preview -> step.error
                else -> null
            },
            "preview" to preview?.name,
            "action" to when (preview?.action) {
                null -> null
                is AddonInstallAction.Install -> "INSTALL"
                is AddonInstallAction.Update -> "UPDATE"
                is AddonInstallAction.Configure -> "CONFIGURE"
                AddonInstallAction.None -> "NONE"
            },
            "configure" to preview?.configureLabel, "result" to (step as? AddonInstallStep.Installed)?.result,
            "consent" to preview?.watchActivity?.checked, "canConfirm" to preview?.canConfirm,
        ),
        "details" to s.details?.let {
            obj(
                "name" to it.name, "linkRevealed" to it.share.revealed, "linkShown" to (it.share.link != null), "enabled" to it.enabled,
                "watchActivity" to it.watchActivity?.checked,
            )
        },
        "removeDialog" to s.removeDialog?.title,
        "notice" to s.notice,
    )

    /**
     * `settings`: the open section, the sections listed with their summaries, About's notices by name (in its order),
     * the notice whose page shows (its name, and once read the first part of its texts' paths, each once: a file's
     * name, or the folder of one of mpv-build's components), and the values of each section shown so far (its store's
     * state; null for one not shown).
     */
    fun settings(
        main: SettingsUiState,
        device: DeviceSettingsUiState?,
        account: AccountSettingsUiState?,
        playback: PlaybackSettingsUiState?,
        player: PlayerSettingsUiState?,
        trackers: TrackersSettingsUiState?,
        home: HomeSettingsUiState?,
        downloads: DownloadSettingsUiState?,
        data: DataSettingsUiState?,
        appearance: AppearanceSettingsUiState?,
        collectionImport: CollectionImportUiState?,
        collectionEditor: CollectionEditorUiState?,
    ): JsonObject = obj(
        "section" to main.section?.name,
        "sections" to main.sections.map { it.section.name },
        "summaries" to main.sections.associate { it.section.name to it.summary },
        "profile" to main.profile?.name,
        "notices" to main.about.notices.map { it.name },
        "notice" to main.about.notice?.let { page ->
            obj("name" to page.name, "texts" to page.texts?.map { it.path.substringBefore('/') }?.distinct())
        },
        "device" to device?.let { d ->
            obj(
                "loading" to d.loading, "hardwareDecoding" to d.hardwareDecoding?.checked, "hdr" to d.hdr?.checked,
                "matchFrameRate" to d.matchFrameRate?.checked, "audioPassthrough" to d.audioPassthrough?.checked,
                "cacheSize" to cache(d.cacheSize), "discordPresence" to d.discordPresence?.checked,
            )
        },
        // The link approval's step (its code stays out), the devices by name, the identities by provider with their
        // emails beside, and the dialog open (its action).
        "account" to account?.let { a ->
            obj(
                "signedIn" to a.signedIn, "status" to a.status, "syncing" to a.syncing, "loading" to a.loading, "loadError" to a.loadError,
                "devices" to a.devices.map { it.name }, "identities" to a.identities.map { it.provider },
                "identityDetails" to a.identities.map { it.detail }, "linkStep" to linkStepName(a.linkApproval?.step),
                "linkError" to (a.linkApproval?.step as? LinkApprovalStep.EnterCode)?.error, "dialog" to a.dialog?.action?.name, "notice" to a.notice,
            )
        },
        "playback" to playback?.let { p ->
            val rows = p.sections.flatMap { it.rows }
            val choices = rows.filterIsInstance<PlaybackSettingRow.Choice>().associate { it.row.id to it.row }
            val toggles = rows.filterIsInstance<PlaybackSettingRow.Toggle>().associate { it.row.id to it.row.checked }
            val include = p.streamPattern(StreamPatternKind.INCLUDE)
            val exclude = p.streamPattern(StreamPatternKind.EXCLUDE)
            obj(
                "loading" to p.loading,
                "bingeWatching" to toggles.getValue("bingeWatching"),
                "showSubtitles" to selected(choices.getValue("showSubtitles")),
                "watchedThreshold" to stepper(rows.filterIsInstance<PlaybackSettingRow.Stepper>().single { it.field == PlaybackNumber.WATCHED_THRESHOLD }.row),
                "skipping" to obj(
                    "intros" to selected(choices.getValue("skipIntros")),
                    "recaps" to selected(choices.getValue("skipRecaps")),
                    "credits" to selected(choices.getValue("skipCredits")),
                ),
                "languagePicker" to p.languagePicker?.let(::languagePicker),
                "streams" to obj(
                    "autoPlay" to selected(choices.getValue("streams.autoPlay")),
                    "qualities" to p.streamChoices(StreamChoicesKind.QUALITIES).valueLabel,
                    "include" to include.value, "includeError" to include.error, "exclude" to exclude.value, "excludeError" to exclude.error,
                    "ranges" to p.streamChoices(StreamChoicesKind.RANGES).valueLabel, "codecs" to p.streamChoices(StreamChoicesKind.CODECS).valueLabel,
                    "minSize" to selected(choices.getValue("streams.minSize")),
                    "maxSize" to selected(choices.getValue("streams.maxSize")),
                ),
            )
        },
        // Playback's Player row and page: the choice, each player, the custom players and the Add custom player form.
        "player" to player?.let { p ->
            obj(
                "value" to p.row.valueLabel, "pageOpen" to p.pageOpen, "detecting" to p.detecting,
                "players" to p.players.map(::playerChoice), "customPlayers" to p.customPlayers.map(::playerChoice),
                "customForm" to p.customForm?.let { f -> obj("kind" to f.kind.name, "error" to f.error) },
            )
        },
        // Each service's connection and switches, the connect flow (its device code only as whether it is up), the
        // disconnect dialog's service and the notice.
        "trackers" to trackers?.let { t ->
            obj(
                "services" to t.services.map { r ->
                    obj(
                        "service" to r.service.id, "status" to r.status, "connected" to r.connected, "username" to r.username,
                        "scrobble" to r.account?.scrobble?.checked, "movies" to r.account?.movies?.checked,
                        "series" to r.account?.series?.checked, "anime" to r.account?.anime?.checked, "queued" to r.account?.queuedCount,
                        "failed" to r.account?.failedCount,
                    )
                },
                "connect" to t.connect?.let { c ->
                    val code = (c.step as? TrackerConnectStep.Code)?.code
                    obj(
                        "service" to c.service.id, "step" to trackerStepName(c.step), "hasCode" to (code != null),
                        "hasQr" to (code?.qrPayload?.isNotEmpty() == true),
                        "waiting" to (c.step is TrackerConnectStep.Code || c.step is TrackerConnectStep.Link), "message" to c.step.message,
                    )
                },
                "dialog" to t.dialog?.service?.id,
                "notice" to t.notice,
            )
        },
        // Home's rows: each row's title, source and switch (a collection's marked), the page open over them.
        "home" to home?.let { h ->
            obj(
                "loading" to (h.body == HomeSettingsBody.Loading), "continueWatching" to h.continueWatching.checked,
                "rows" to (h.body as? HomeSettingsBody.Rows)?.rows.orEmpty().map {
                    obj("title" to it.title, "source" to it.sourceName, "shown" to it.shown, "collection" to (it.collectionId != null))
                },
                "canReset" to h.canReset, "canExport" to h.collections.canExport, "page" to h.page?.name,
                "deleting" to h.deleteDialog?.title, "notice" to h.notice,
            )
        },
        // The collection import: its step, the summary's collections and lines, the message.
        "collectionImport" to collectionImport?.let { i ->
            val step = i.step
            obj(
                "step" to when (step) {
                    CollectionImportStep.Idle -> "IDLE"
                    CollectionImportStep.Loading -> "LOADING"
                    is CollectionImportStep.Failed -> "FAILED"
                    is CollectionImportStep.Summary -> "SUMMARY"
                    is CollectionImportStep.Done -> "DONE"
                },
                "message" to ((step as? CollectionImportStep.Failed)?.message ?: (step as? CollectionImportStep.Done)?.message),
                "collections" to (step as? CollectionImportStep.Summary)?.collections?.map {
                    obj("title" to it.title, "detail" to it.detail, "folders" to it.folders, "replaces" to it.replaces)
                },
                "lines" to (step as? CollectionImportStep.Summary)?.lines,
                "canImport" to (step as? CollectionImportStep.Summary)?.canImport,
            )
        },
        // The collection editor: the collection's title, view, tile shape and folders, the folder page (its title and
        // sources), the picker's groups, the refusal, and whether Back's discard question is up.
        "collectionEditor" to collectionEditor?.let { e ->
            obj(
                "loaded" to e.loaded, "title" to e.title, "viewMode" to selected(e.viewMode), "allTab" to e.showAllTab.checked,
                "shape" to selected(e.shape), "folders" to e.folders.map { it.title }, "error" to e.error, "saving" to e.saving,
                "discard" to (e.discard != null),
                "folder" to e.folder?.let { f ->
                    obj(
                        "title" to f.title, "cover" to f.coverUrl.isNotEmpty(), "hideTitle" to f.hideTitle.checked,
                        "sources" to f.sources.map { obj("title" to it.title, "detail" to it.detail, "note" to it.note) },
                    )
                },
                "picker" to e.picker?.let { p ->
                    obj(
                        "groups" to p.groups.map { g -> obj("title" to g.title, "choices" to g.choices.size, "loading" to g.loading, "notes" to g.notes) },
                        "urlForm" to p.urlForm?.let { u -> obj("title" to u.title, "resolving" to u.resolving, "error" to u.error) },
                    )
                },
            )
        },
        // Appearance: the interface language, the switches and whether the poster template's row shows (never the
        // template: it holds a key).
        "appearance" to appearance?.let { a ->
            obj(
                // A stored language this build has no strings for has no option selected.
                "interfaceLanguage" to a.interfaceLanguage.options.firstOrNull { it.selected }?.id, "blurUnwatched" to a.blurUnwatched.checked,
                "hideWatched" to a.hideWatched.checked, "hidePosterCaptions" to a.hidePosterCaptions.checked,
                "posterTemplate" to (a.posterTemplate != null),
            )
        },
        "downloads" to downloads?.let { d ->
            obj(
                "loading" to d.loading, "folder" to d.folder?.path, "location" to d.location?.valueLabel, "wifiOnly" to d.wifiOnly?.checked,
                "atOnce" to stepper(d.atOnce), "autoRemoveWatched" to d.autoRemoveWatched.checked,
                "subtitleLanguages" to d.subtitleLanguages.valueLabel, "languagePicker" to d.languagePicker?.let(::languagePicker),
                "storage" to d.storage,
                "deleteAll" to obj("label" to d.deleteAll.label, "enabled" to d.deleteAll.enabled, "asking" to (d.deleteAllDialog != null)),
            )
        },
        // Export and import: the export's ticks, the warning, the notice, the file being saved (its name), and the
        // review of an opened file (its ticks and each profile's target). Never the file's text.
        "data" to data?.let { d ->
            obj(
                "loading" to d.loading,
                "working" to d.working,
                "categories" to d.exportCategories.associate { it.category.name.lowercase() to it.toggle.checked },
                "profiles" to d.exportProfiles.map { it.toggle.checked },
                "private" to (d.privacyNote != null),
                "canExport" to d.canExport,
                "canImport" to d.canImport,
                "notice" to d.notice?.let { obj("text" to it.text, "error" to it.error) },
                "saving" to d.saveRequest?.fileName,
                "review" to d.review?.let { r ->
                    obj(
                        "source" to r.source,
                        "categories" to r.categories.associate { it.category.name.lowercase() to it.toggle.checked },
                        "profiles" to r.profiles.map { p ->
                            obj(
                                "name" to p.toggle.title, "checked" to p.toggle.checked,
                                "target" to p.target?.options?.firstOrNull { it.selected }?.id, "targetLabel" to p.target?.valueLabel,
                                "options" to p.target?.options?.map { it.label },
                            )
                        },
                        "canConfirm" to r.canConfirm,
                    )
                },
            )
        },
    )

    private fun languagePicker(l: LanguagePickerUiState) = obj(
        "kind" to l.kind.name, "chosen" to l.chosen.map { it.code }, "query" to l.query,
        "available" to l.available.size, "firstAvailable" to l.available.take(5).map { it.code },
    )

    private fun selected(c: SettingChoiceRow): String = c.options.first { it.selected }.id

    private fun playerChoice(r: PlayerChoiceRow) = obj("id" to r.id, "name" to r.name, "detail" to r.detail, "selected" to r.selected)

    private fun stepper(n: SettingStepperRow) = obj(
        "value" to n.value, "label" to n.valueLabel, "canDecrease" to n.canDecrease, "canIncrease" to n.canIncrease,
    )

    /** The cache size choice: the selected size in MiB (null for Automatic), as the stored setting reads. */
    private fun cache(c: SettingChoiceRow): JsonObject {
        val megabytes = c.options.firstOrNull { it.selected }?.id?.toIntOrNull()
        return obj("value" to megabytes, "automatic" to (megabytes == null), "label" to c.valueLabel)
    }

    /**
     * `account`, the account shell: the gate (destination, profile, sync status, notice; null in a development session,
     * which has no shell) and the store of each account screen on screen, by its key ([stores]: sign-in, identity link,
     * profiles, profile editor; a development session's app shows the profile editor too).
     */
    fun account(root: RootSession?, stores: (String) -> Any?): JsonObject {
        val gate = root?.gateState?.value
        return obj(
            "destination" to gate?.destination?.let(::destinationName),
            "profileId" to (gate?.destination as? AccountDestination.Main)?.profileId,
            "syncKind" to gate?.syncKind?.let(::syncKindName),
            "syncStatus" to gate?.syncStatus,
            "notice" to gate?.notice,
            "signIn" to (stores(SignInStore.KEY) as? SignInStore)?.state?.value?.let(::signIn),
            "identityLink" to (stores(IdentityLinkStore.KEY) as? IdentityLinkStore)?.state?.value?.let(::identityLink),
            "profiles" to (stores(ProfilePickerStore.KEY) as? ProfilePickerStore)?.state?.value?.let(::profiles),
            "profileEditor" to (stores(ProfileEditorStore.KEY) as? ProfileEditorStore)?.state?.value?.let(::profileEditor),
        )
    }

    /** Sign-in: its step, the server, the providers offered by method, the device link and a device flow's code. */
    private fun signIn(s: SignInUiState) = obj(
        "step" to when (s.step) {
            is SignInStep.Choose -> "CHOOSE"
            is SignInStep.Working -> "WORKING"
            is SignInStep.DeviceCode -> "DEVICE_CODE"
            is SignInStep.Link -> "LINK"
        },
        "server" to s.server.label,
        "serverEditing" to s.server.editing,
        "serverChecking" to s.server.checking,
        "serverError" to s.server.error,
        "options" to s.options.map { obj("provider" to it.provider, "method" to it.method.name, "label" to it.label) },
        "optionsLoading" to s.optionsLoading,
        "optionsNote" to s.optionsNote,
        "progress" to when (val step = s.step) {
            is SignInStep.Working -> step.progress
            is SignInStep.DeviceCode -> step.progress
            is SignInStep.Choose, is SignInStep.Link -> null
        },
        "error" to (s.step as? SignInStep.Choose)?.error,
        "link" to (s.step as? SignInStep.Link)?.link?.let(::link),
        "deviceCode" to (s.step as? SignInStep.DeviceCode)?.code?.userCode,
    )

    /** "Add a sign-in method": its step, the providers offered by method, and a device flow's code (as sign-in's). */
    private fun identityLink(s: IdentityLinkUiState): JsonObject {
        val step = s.step
        val options = (step as? IdentityLinkStep.Choose)?.options
        return obj(
            "step" to when (step) {
                is IdentityLinkStep.Choose -> "CHOOSE"
                is IdentityLinkStep.Working -> "WORKING"
                is IdentityLinkStep.DeviceCode -> "DEVICE_CODE"
            },
            "options" to (options as? IdentityLinkOptions.Buttons)?.options.orEmpty().map { obj("provider" to it.provider, "method" to it.method.name) },
            "loading" to (options == IdentityLinkOptions.Loading),
            "progress" to when (step) {
                is IdentityLinkStep.Working -> step.progress
                is IdentityLinkStep.DeviceCode -> step.progress
                is IdentityLinkStep.Choose -> null
            },
            "error" to (step as? IdentityLinkStep.Choose)?.error,
            "deviceCode" to (step as? IdentityLinkStep.DeviceCode)?.code?.userCode,
        )
    }

    private fun link(l: DeviceLinkUiState) = when (l) {
        is DeviceLinkUiState.Starting -> obj("status" to "STARTING", "message" to l.progress)
        is DeviceLinkUiState.Waiting ->
            obj("status" to "WAITING", "code" to l.code, "hasQr" to true, "expires" to l.expiresText, "message" to l.progress)
        is DeviceLinkUiState.Failed -> obj("status" to "FAILED", "message" to l.error)
    }

    private fun linkStepName(step: LinkApprovalStep?) = when (step) {
        null -> "CLOSED"
        is LinkApprovalStep.EnterCode -> "ENTER_CODE"
        LinkApprovalStep.Checking -> "CHECKING"
        is LinkApprovalStep.Confirm -> "CONFIRM"
        LinkApprovalStep.Approving -> "APPROVING"
        LinkApprovalStep.Rejecting -> "REJECTING"
        is LinkApprovalStep.Approved -> "APPROVED"
        is LinkApprovalStep.Rejected -> "REJECTED"
    }

    private fun trackerStepName(step: TrackerConnectStep) = when (step) {
        is TrackerConnectStep.Starting -> "STARTING"
        is TrackerConnectStep.Code -> "CODE"
        is TrackerConnectStep.Link -> "LINK"
        is TrackerConnectStep.Expired -> "EXPIRED"
        is TrackerConnectStep.Declined -> "DECLINED"
        is TrackerConnectStep.Failed -> "FAILED"
    }

    private fun profiles(p: ProfilePickerUiState) = obj(
        "title" to p.title,
        "loading" to (p.body == ProfilePickerBody.Loading),
        "managing" to p.managing,
        "profiles" to (p.body as? ProfilePickerBody.Tiles)?.profiles.orEmpty().map { obj("name" to it.name, "locked" to it.locked, "current" to it.current) },
        "pin" to p.pinPrompt?.let { obj("title" to it.title, "entered" to it.entered, "error" to it.error, "acceptsInput" to it.acceptsInput) },
        "syncStatus" to p.syncStatus,
    )

    private fun profileEditor(e: ProfileEditorUiState) = obj(
        "title" to e.title,
        "creating" to e.creating,
        "name" to e.name,
        "hasPin" to e.hasPin,
        "pinSetup" to e.pinSetup?.let { obj("step" to it.step.name, "entered" to it.entered, "error" to it.error) },
        "canSave" to e.canSave,
        "saving" to e.saving,
        "error" to e.error,
    )

    /** `overlay`, the app's overlay over its screens: editProfile, linkIdentity, openOnPhone or parentPin. */
    fun overlay(o: AppOverlay): JsonPrimitive = JsonPrimitive(
        when (o) {
            is AppOverlay.EditProfile -> "editProfile"
            AppOverlay.LinkIdentity -> "linkIdentity"
            is AppOverlay.OpenOnPhone -> "openOnPhone"
            AppOverlay.ParentPin -> "parentPin"
        },
    )

    /** `nowPlaying`, what the OS's media controls show: the title, its artwork's presence, the status, position and actions. */
    fun nowPlaying(n: NowPlaying): JsonObject = obj(
        "title" to n.title, "subtitle" to n.subtitle, "artwork" to (n.artworkUrl != null), "status" to n.status.name,
        "positionSeconds" to n.positionSeconds, "durationSeconds" to n.durationSeconds, "rate" to n.rate,
        "canSeek" to n.canSeek, "canSkipNext" to n.canSkipNext, "canSkipPrevious" to n.canSkipPrevious,
    )

    /**
     * `stored`, what the database holds for the Settings and Addons screens: the [device]'s settings, the [profile]'s
     * and its addons' names in order ([addons]). Addon links never appear.
     */
    fun stored(device: DeviceSettings, profile: ProfileSettings, addons: List<String>): JsonObject = obj(
        "device" to obj(
            "hardwareDecoding" to device.hardwareDecoding, "hdr" to device.hdr, "matchFrameRate" to device.matchFrameRate,
            "audioPassthrough" to device.audioPassthrough, "cacheSizeMegabytes" to device.cacheSizeMegabytes,
            "discordPresence" to device.discordPresence,
            "downloadLocation" to device.downloadLocation,
            "downloadsAtOnce" to device.downloadsAtOnce, "downloadAutoRemoveWatched" to device.downloadAutoRemoveWatched,
            "downloadSubtitleLanguages" to device.downloadSubtitleLanguages,
            "player" to device.player,
            "customPlayers" to device.customPlayers.map {
                obj("id" to it.id, "name" to it.name, "command" to it.command, "arguments" to it.arguments)
            },
        ),
        "profile" to obj(
            "watchedThresholdPercent" to profile.watchedThresholdPercent, "audioLanguages" to profile.audioLanguages,
            "subtitleLanguages" to profile.subtitleLanguages, "showSubtitles" to profile.showSubtitles.name,
            "bingeWatching" to profile.bingeWatching, "blurUnwatched" to profile.blurUnwatched, "hideWatched" to profile.hideWatched,
            "hidePosterCaptions" to profile.hidePosterCaptions,
            "upNextCountdownSeconds" to profile.upNextCountdownSeconds, "seekShortSeconds" to profile.seekShortSeconds,
            "seekLongSeconds" to profile.seekLongSeconds, "interfaceLanguage" to profile.interfaceLanguage,
            "skipIntros" to profile.skipIntros.name, "skipRecaps" to profile.skipRecaps.name,
            "skipCredits" to profile.skipCredits.name, "streamAutoPlay" to profile.streamAutoPlay.name,
            "videoClick" to profile.videoClick.name,
        ),
        "addons" to addons,
    )

    /**
     * `player`, as the player screen shows it ([p], and [timeline] for the position): its status, pause and position
     * label, the controls, menus and info (`info` its rows' count), the track menus (each entry's track id, label,
     * detail, selection), the options, the prompts, the episode buttons, the watch-together controls, a swipe's level,
     * a held finger's speed and the screen lock (`lock` its options entry's label; `screenLock` while locked, its
     * unlock button null while hidden).
     */
    fun player(p: PlayerUiState, timeline: TimelineState): JsonObject = obj(
        "title" to p.title,
        "status" to p.status.name,
        "paused" to p.paused,
        "position" to timeline.positionLabel,
        "keepAwake" to p.keepAwake,
        // A live window's timeline (PlaybackMode.LIVE_WINDOW): its place and length in seconds, at the edge or how far behind.
        "rewind" to timeline.takeIf { p.mode == PlaybackMode.LIVE_WINDOW }?.let {
            obj("window" to it.durationSeconds, "at" to it.positionSeconds, "atEdge" to it.atLiveEdge, "behind" to it.behind)
        },
        "controlsVisible" to p.controlsVisible,
        "menu" to p.menu?.name,
        "fullscreen" to p.fullscreen,
        "hdrPicture" to p.hdrPicture,
        "scrubbing" to p.scrubbing,
        "infoVisible" to p.infoVisible,
        "info" to p.info.sumOf { it.rows.size },
        "infoRows" to p.info.associate { section -> section.id.name to section.rows.associate { it.label to it.value } },
        "speed" to p.speedLabel,
        "volume" to p.volume.label,
        "level" to p.level?.let { obj("kind" to it.kind.name, "fraction" to it.fraction, "label" to it.label) },
        "heldSpeed" to p.heldSpeed,
        "lock" to p.lockLabel,
        "screenLock" to p.screenLock?.let { lock -> obj("unlock" to lock.unlock?.let { obj("armed" to it.armed, "label" to it.label) }) },
        "error" to p.error?.let { obj("title" to it.title, "message" to it.message, "errorVideo" to it.errorVideo) },
        "audioTracks" to p.audioTracks.cut().map(::trackItem),
        "subtitleTracks" to p.subtitleTracks.cut().map(::trackItem),
        "moreSubtitles" to p.moreSubtitles.cut().map { obj("key" to it.key, "label" to it.label, "count" to it.count) },
        "subtitleLanguage" to p.subtitleLanguage?.let { l -> obj("label" to l.label, "subtitles" to l.subtitles.cut().map { it.label }) },
        "options" to p.options.associate { it.id.name to it.value },
        "adjusted" to p.options.filter { it.adjusted }.map { it.id.name },
        "stillWatching" to p.stillWatching?.countdown,
        "prompt" to p.prompt?.let { obj("kind" to it.kind.name, "label" to it.label, "progress" to it.progress) },
        "episodes" to p.episodes?.let { obj("previous" to it.previous, "next" to it.next) },
        "watch" to p.watch?.let(::playerWatch),
    )

    private fun trackItem(t: TrackItem) = obj("id" to t.id, "label" to t.label, "detail" to t.detail, "selected" to t.selected, "second" to t.second)

    /**
     * The player's part in the room: the room, the indicator's count, the pill, a guest's search or prompt with its
     * actions' commands, this device's offset, the lock, the line of a player paused here alone. A command reads as its
     * toString (a data object's declared name, "PlayAnyway"): R8 renames the classes in Android's harness build, so
     * `::class.simpleName` would read "jo6".
     */
    private fun playerWatch(w: PlayerWatchUi) = obj(
        "room" to w.room?.let(::room),
        "count" to (w.indicator?.count ?: 0),
        "pill" to w.pill,
        "matching" to w.matching,
        "prompt" to w.prompt?.message,
        "promptActions" to w.prompt?.actions?.map { it.command.toString() },
        "offset" to w.offset?.let { offset -> obj("value" to offset.value, "actions" to offset.actions.map { it.label }) },
        "canControl" to w.canControl,
        "pausedHere" to w.pausedHere,
        "busy" to w.busy,
        "failure" to w.failure,
    )

    /** `watch`, the lobby: the relay check and setting, joining, work in progress, the last failure, recent rooms' codes and the room. */
    fun watch(s: WatchLobbyUiState): JsonObject = obj(
        "relay" to s.relay.status,
        "relaySetting" to s.relaySetting.valueLabel,
        "canJoin" to s.canJoin,
        "joinError" to s.joinError,
        "busy" to s.busy,
        "failure" to s.failure,
        "recent" to s.recentRooms.cut().map { it.code },
        "room" to s.room?.let(::room),
    )

    /** A room: who controls playback (and the host's choices), the lock, End (the host's), the participants. */
    private fun room(r: WatchRoomUi) = obj(
        "code" to r.code,
        "host" to r.isHost,
        "nowPlaying" to r.nowPlaying,
        "controlMode" to r.selectedMode.name,
        "controlChoices" to r.controlChoices.map { it.mode.name },
        "lock" to r.lock?.checked,
        "locked" to (r.lock?.checked ?: (r.lockedText != null)),
        "canEnd" to (r.end != null),
        "participants" to r.participants.map {
            obj(
                "name" to it.name, "host" to it.isHost, "status" to it.status, "tone" to it.tone.name, "offset" to it.offset,
                "canMakeHost" to it.canMakeHost, "canKick" to (it.kick != null),
            )
        },
    )

    /**
     * The player engine's state (Android's `sample` line, the desktop sample's top level): position and duration in
     * seconds, the pause, buffering, load and end marks, the cache, the error, the settings, hwdec in use, the playback
     * restarts of the file, the video and audio as decoded and the chapters.
     */
    fun engine(s: EngineState): JsonObject = obj(
        "position" to s.positionSeconds,
        "duration" to s.durationSeconds,
        "paused" to s.paused,
        "buffering" to s.buffering,
        "loaded" to s.loaded,
        "eof" to s.eof,
        "cacheEndSeconds" to s.cacheEndSeconds,
        "error" to s.error?.let { obj("message" to it.message, "httpStatus" to it.httpStatus) },
        "speed" to s.speed,
        "subtitleDelaySeconds" to s.subtitleDelaySeconds,
        "audioDelaySeconds" to s.audioDelaySeconds,
        "volume" to s.volume,
        "muted" to s.muted,
        "hwdec" to s.hwdec,
        // Per file: a live rewind's reopen starts them over, a seek within what mpv holds counts one more.
        "playbackRestarts" to s.playbackRestarts,
        "video" to s.video?.let {
            obj("w" to it.width, "h" to it.height, "codec" to it.codec, "fps" to it.fps, "range" to it.dynamicRange.name, "dovi" to it.doviProfile)
        },
        "audio" to s.audio?.let { obj("codec" to it.codec, "channels" to it.channels, "sampleRate" to it.sampleRate) },
        "chapters" to s.chapters.cut().map { obj("title" to it.title, "startSeconds" to it.startSeconds) },
    )

    /** The engine's tracks (Android's `tracks` line, the desktop sample's `tracks`), every one. */
    fun tracks(s: EngineState): JsonArray = JsonArray(
        s.tracks.map {
            obj(
                "id" to it.id, "kind" to it.kind.name, "codec" to it.codec, "language" to it.language, "title" to it.title,
                "selected" to it.selected, "secondary" to it.secondary, "external" to it.external, "default" to it.isDefault,
                "forced" to it.forced, "hearingImpaired" to it.hearingImpaired, "visualImpaired" to it.visualImpaired,
                "channels" to it.channels,
            )
        },
    )

    /**
     * One download as the device's downloads hold it (Android's `downloads` line, the desktop sample's `queue`): its
     * video, state, failure, bytes and size, and Keep synced. No URL, no path, no title.
     */
    fun download(d: Download): JsonObject = obj(
        "id" to d.id,
        "videoId" to d.videoId,
        "season" to d.season,
        "episode" to d.episode,
        "status" to d.status.name,
        "failure" to d.failure?.let { "${it.kind.name}${it.httpStatus?.let { s -> " $s" }.orEmpty()}" },
        "bytes" to d.bytes,
        "totalBytes" to d.totalBytes,
        "keepSynced" to d.keepSyncedEpisode,
    )

    private fun <T> List<T>.cut(): List<T> = take(listLimit)
}

/**
 * A report value as JSON: null, a JSON element, a string, number or boolean, a map (its keys as text, in its order), an
 * iterable, or anything else as its text (an envelope's event detail can carry any value).
 */
fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}

private fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(fields.associate { (k, v) -> k to toJson(v) })

/** [destination] by the name the report has always given it. */
private fun destinationName(destination: AccountDestination): String = when (destination) {
    AccountDestination.Starting -> "STARTING"
    AccountDestination.SignIn -> "SIGN_IN"
    AccountDestination.Profiles -> "PROFILES"
    is AccountDestination.Main -> "MAIN"
}

/** [kind] by the name the report has always given it. */
private fun syncKindName(kind: SyncStatus.Kind): String = when (kind) {
    SyncStatus.Kind.Idle -> "IDLE"
    SyncStatus.Kind.Syncing -> "SYNCING"
    is SyncStatus.Kind.Error -> "ERROR"
    SyncStatus.Kind.Offline -> "OFFLINE"
    SyncStatus.Kind.SignedOut -> "SIGNED_OUT"
}
