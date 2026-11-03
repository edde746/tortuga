package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailStore
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.components.BlockedMessage
import com.edde746.tortuga.ui.components.ErrorState
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.TimedToast
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.library.DownloadDeleteOverlay
import com.edde746.tortuga.ui.probe.DETAIL_CONTENT_MARK
import com.edde746.tortuga.ui.probe.DETAIL_EPISODES_MARK
import com.edde746.tortuga.ui.probe.DETAIL_HEADER_MARK
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.perfMark
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import kotlinx.coroutines.flow.first

/**
 * The Detail screen of [route] in [app]: its [DetailStore] lives while the route is on the back stack, so coming back
 * from the player finds the page as it was, on the same season, with focus on what was opened.
 */
@Composable
fun DetailRoute(app: AppSession, route: Route.Detail) {
    val navigator = app.navigator
    val platform = LocalTortugaPlatform.current
    // Once per appearance: the entry still draws while it animates out after its route left the stack (its store
    // released), and an entry first composed after that gets no store and draws nothing.
    val store = remember(route) {
        navigator.retain(
            route,
            key = DetailStore.KEY,
            create = { app.detailStore(route) },
            release = DetailStore::release,
        )
    } ?: return
    val state by store.state.collectAsState()
    val season = state.selectedSeason
    // "Link copied." after Share where the platform has no share sheet (Windows, Linux), until its toast is over.
    var linkCopied by remember { mutableStateOf<String?>(null) }
    val intents = remember(store) {
        DetailIntents(
            play = store::play,
            chooseStream = store::chooseStream,
            selectSeason = store::selectSeason,
            openEpisode = store::openEpisode,
            openLink = store::openLink,
            openTrailer = store::openTrailer,
            toggleLibrary = { if (store.state.value.inLibrary) store.removeFromLibrary() else store.addToLibrary() },
            markWatched = store::markWatched,
            seasonMenu = store::seasonMenuAction,
            episodeMenu = store::episodeMenuAction,
            pressDownload = store::pressDownload,
            downloadAction = store::downloadAction,
            dismissDownloadNotice = store::dismissDownloadNotice,
            confirmDownloadDelete = store::confirmDownloadDelete,
            dismissDownloadDelete = store::dismissDownloadDelete,
            answerPlayerApp = store::answerPlayerApp,
            episodeShown = store::episodeShown,
            pickRange = store::pickRange,
            share = {
                store.state.value.share?.let { share ->
                    if (platform.canShare) {
                        platform.share(share.url)
                    } else {
                        platform.setClipboardText(share.url)
                        linkCopied = share.copiedText
                    }
                }
            },
            rate = store::rate,
            watchlist = store::watchlist,
            openRelated = store::openRelated,
            relatedMenu = store::relatedMenuAction,
            reload = store::reload,
            askParent = store::askParent,
            blockOnProfile = store::blockOnProfile,
        )
    }
    DetailScreen(
        state = state,
        intents = intents,
        linkCopied = linkCopied,
        onLinkCopiedShown = { linkCopied = null },
        onBack = if (navigator.canGoBack) ({ navigator.back() }) else null,
        // The perf runs' marks: the title's header on screen (a catalog item's preview, else the title's), the whole
        // title, then a series' selected season's episodes.
        modifier = Modifier
            .perfMark(DETAIL_HEADER_MARK, "type=${route.type} id=${route.id}", ready = state.body is DetailBody.Page)
            .perfMark(DETAIL_CONTENT_MARK, "type=${route.type} id=${route.id}", ready = state.body is DetailBody.Loaded)
            .perfMark(DETAIL_EPISODES_MARK, "season=$season count=${state.episodes.size}", ready = state.body is DetailBody.Loaded && season != null && state.episodes.isNotEmpty()),
    )
}

/**
 * One title. Android TV has a page of its own after Prime Video's ([TvDetailPage]); phones,
 * tablets and desktop are laid out like the Apple TV app's pages: the backdrop full-bleed (across the top on narrow
 * windows, fading into the page; behind the whole screen on wide ones), the title's logo (else its name), one metadata
 * line, the ratings row (the addon's IMDb rating, the connected trackers' scores, then the user's own rating, which
 * opens the rating picker and rates through [DetailIntents.rate]), one row of actions (Play/Resume
 * with the watched part filled inside it, with auto-play on a round button opening the stream list instead, then round
 * buttons for the library, the watched mark, the download and the trailers), the description (three lines, then More)
 * and the title's information, its genres, cast, directors and writers as text links. Narrow windows centre the logo,
 * the metadata line, the ratings and the actions under the backdrop. A series adds its seasons as a row of chips (a
 * long season's ranges under them, [SeasonChips]; a season's chip's menu, [DetailSeason.menu], picked through
 * [DetailIntents.seasonMenu]; off TV a range's chip picks the range listed through [DetailIntents.pickRange]) and the
 * season's episodes as free-flowing rows; wide windows put those in a column beside the details. "More like this"
 * ([DetailUiState.related], [RelatedShelf]) comes before the information, its cards opening their titles
 * ([DetailIntents.openRelated]) and their menus picked through [DetailIntents.relatedMenu]. The poster shows only
 * when there is no backdrop.
 *
 * Every text comes from [DetailUiState]; what the page asks of its store goes through [intents]. An episode plays
 * auto-play's pick or opens its streams ([DetailIntents.openEpisode]); its menu (a right click, a long press, the Menu
 * key, a hold of Select on TV or its ⋮ button) lists its media menu ([DetailEpisode.menu], picked through
 * [DetailIntents.episodeMenu]): Play from the beginning, its marks and its season's, Choose a stream, its download's
 * entries and its trailer. The download button ([DetailUiState.download]) presses through
 * [DetailIntents.pressDownload] or opens its menu ([DetailIntents.downloadAction]); what a "Download…" choice came to
 * shows as a toast at the bottom ([DetailIntents.dismissDownloadNotice] once shown), the question before a season's or
 * the whole title's downloads go ([DetailUiState.downloadDelete]) as a dialog (`detail.downloadDelete.<cancel|confirm>`:
 * [DetailIntents.confirmDownloadDelete], [DetailIntents.dismissDownloadDelete]), and the saved title's note
 * ([DetailUiState.offlineNote]) under the actions. The title's playback in a player app ([DetailUiState.playerApp])
 * shows under the actions too, with the "Finished watching?" answers ([DetailIntents.answerPlayerApp]). Beside the
 * details (wide windows, TV's episode row) the episodes are brought to [DetailUiState.shownEpisode] once, centred in
 * what shows of their list ([DetailIntents.episodeShown] then), and a move into them with the arrow keys or a D-pad
 * lands on [DetailUiState.currentEpisode]; the one-column page brings its list to an anime list entry's first episode
 * alone ([DetailUiState.shownEpisodeFromEntry]), at the top under the back button. Share ([DetailUiState.share],
 * [DetailIntents.share]) hands the title's page to the share sheet, or copies it where there is none, [linkCopied]
 * then showing as a toast ([onLinkCopiedShown] once shown). The page is what [DetailUiState.body] says: while the title
 * loads a spinner, or the page of the catalog item it was opened from ([DetailBody.Preview]), on TV either with focus
 * on the page itself (`detail.page`) until the title's primary action takes it; when no addon has the
 * title, the error says why, with the meta addons that failed under it and Retry ([DetailIntents.reload])
 * ([DetailBody.Failed]). A title blocked on a kids profile ([DetailBody.Blocked]) shows only that, with Ask a parent
 * ([DetailIntents.askParent]), on every form factor; an allowed one offers Block on this profile
 * ([DetailUiState.kidsBlockAction], [DetailIntents.blockOnProfile]) behind ⋯ (TV: last in the row).
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    intents: DetailIntents,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    linkCopied: String? = null,
    onLinkCopiedShown: () -> Unit = {},
) {
    val focus = rememberFocusMemory()
    val body = state.body
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    // On TV, while the title loads (the spinner, or the page of the item it was opened from, which has no actions) the
    // page itself holds focus, so focus neither lands on the side navigation, opening it over the page, nor stays on
    // the screen left behind; kept until focus has moved on to what the title brings.
    val waiting = tv && (body == DetailBody.Loading || body is DetailBody.Preview)
    var pageFocused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        if (waiting || pageFocused) {
            Box(Modifier.matchParentSize().rememberFocus(focus, FOCUS_PAGE).onFocusChanged { pageFocused = it.isFocused }.layoutProbe("detail.page").focusable())
        }
        when (body) {
            DetailBody.Loading -> LoadingState(text = state.loadingNote)
            is DetailBody.Page -> DetailPage(state, body.header, focus, intents)
            is DetailBody.Failed -> ErrorState(body.message, title = body.title, onRetry = intents.reload)
            is DetailBody.Blocked -> BlockedMessage(body.title, body.reason, body.askLabel, intents.askParent, "detail.kidsBlock")
        }
        // A remote has its own Back: TV shows no button to walk past. Over the backdrop: a translucent circle.
        if (onBack != null && !tv) {
            TortugaIconButton(
                TortugaIcons.ArrowBack,
                T.nav.back,
                onBack,
                Modifier.align(Alignment.TopStart).padding(12.dp).layoutProbe("header.back"),
                style = IconButtonStyle.OVER_PICTURE,
            )
        }
        state.downloadNotice?.let {
            TimedToast(it, "detail.downloadNotice", intents.dismissDownloadNotice, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalTortugaDimens.current.sectionSpacing))
        }
        linkCopied?.let {
            TimedToast(it, "detail.linkCopied", onLinkCopiedShown, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalTortugaDimens.current.sectionSpacing))
        }
    }
    state.downloadDelete?.let { DownloadDeleteOverlay(it, "detail.downloadDelete", intents.confirmDownloadDelete, intents.dismissDownloadDelete) }
    // The page itself while the title loads on TV, then the primary action (else the library toggle) the first time,
    // once the title is known: focus the page placed follows to it. Afterwards what had focus (the episode opened).
    // Each time the page comes on view without focus too (a stream list opened over it from Home closing): RestoreFocus
    // waits for that.
    val fallback = when {
        waiting -> FOCUS_PAGE
        state.primaryAction != null -> FOCUS_PLAY
        else -> FOCUS_LIBRARY
    }
    RestoreFocus(focus, ready = waiting || body is DetailBody.Loaded, fallback = fallback)
}

/** The TV page itself, which holds focus while the title loads. */
private const val FOCUS_PAGE = "page"

/** What the Detail page asks of [DetailStore]; the route builds it once from the store's methods. */
class DetailIntents(
    val play: () -> Unit,
    val chooseStream: () -> Unit,
    val selectSeason: (Int) -> Unit,
    val openEpisode: (String) -> Unit,
    val openLink: (String) -> Unit,
    val openTrailer: (String) -> Unit,
    val toggleLibrary: () -> Unit,
    val markWatched: (Boolean) -> Unit,
    val seasonMenu: (season: Int, MediaAction) -> Unit,
    val episodeMenu: (videoId: String, MediaAction) -> Unit,
    val pressDownload: () -> Unit,
    val downloadAction: (String) -> Unit,
    val dismissDownloadNotice: () -> Unit,
    val confirmDownloadDelete: () -> Unit,
    val dismissDownloadDelete: () -> Unit,
    val answerPlayerApp: (finished: Boolean) -> Unit,
    val episodeShown: () -> Unit,
    val pickRange: (Int) -> Unit,
    val share: () -> Unit,
    val rate: (Int?) -> Unit,
    val watchlist: () -> Unit,
    val openRelated: (MetaCard) -> Unit,
    val relatedMenu: (MetaCard, MediaAction) -> Unit,
    val reload: () -> Unit,
    val askParent: () -> Unit,
    val blockOnProfile: () -> Unit,
)

@Composable
private fun DetailPage(state: DetailUiState, header: DetailHeader, focus: FocusMemory, intents: DetailIntents) {
    if (LocalWindowLayout.current.formFactor == FormFactor.TV) {
        TvDetailPage(state, header, focus, intents)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= WideLayoutWidth) {
            WidePage(state, header, focus, intents, maxWidth, maxHeight)
        } else {
            NarrowPage(state, header, focus, intents, maxWidth, maxHeight)
        }
    }
}
