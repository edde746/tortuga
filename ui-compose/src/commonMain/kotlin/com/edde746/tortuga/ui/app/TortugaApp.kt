package com.edde746.tortuga.ui.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.AppOverlay
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.BackStackLayout
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.navigation.TortugaNavigator
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.engine.SyncStatus
import com.edde746.tortuga.ui.account.IdentityLinkRoute
import com.edde746.tortuga.ui.account.QrTile
import com.edde746.tortuga.ui.account.qrBeside
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.NavigationKind
import com.edde746.tortuga.ui.board.BoardRoute
import com.edde746.tortuga.ui.board.CollectionFolderRoute
import com.edde746.tortuga.ui.calendar.CalendarRoute
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.LocalPosterTemplate
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.OverlayPage
import com.edde746.tortuga.ui.components.SystemBackHandler
import com.edde746.tortuga.ui.components.TimedToast
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.detail.DetailRoute
import com.edde746.tortuga.ui.discover.DiscoverRoute
import com.edde746.tortuga.ui.focus.FocusVisibility
import com.edde746.tortuga.ui.focus.LocalFocusVisibility
import com.edde746.tortuga.ui.focus.LocalScreenFocus
import com.edde746.tortuga.ui.focus.ScreenFocus
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.focus.focusGate
import com.edde746.tortuga.ui.focus.trackFocusVisibility
import com.edde746.tortuga.ui.guide.GuideRoute
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.ProvideImageLoader
import com.edde746.tortuga.ui.library.KeepSyncedRoute
import com.edde746.tortuga.ui.library.LibraryRoute
import com.edde746.tortuga.ui.navigation.LocalStatusBarOverlap
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.player.PlayerRoute
import com.edde746.tortuga.ui.players.PlayerAppBannerLine
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.profiles.ParentPinRoute
import com.edde746.tortuga.ui.profiles.ProfileAvatarTile
import com.edde746.tortuga.ui.profiles.ProfileEditorRoute
import com.edde746.tortuga.ui.search.SearchRoute
import com.edde746.tortuga.ui.settings.SettingsRoute
import com.edde746.tortuga.ui.streams.StreamsRoute
import com.edde746.tortuga.ui.streams.StreamsSheet
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import com.edde746.tortuga.ui.theme.contentDirection
import com.edde746.tortuga.ui.watch.WatchRoute

/**
 * A development session's app: [app] alone, without the account shell ([TortugaRoot] runs the product), on
 * [platform].
 */
@Composable
fun TortugaApp(app: AppSession, platform: TortugaPlatform, modifier: Modifier = Modifier) {
    TortugaFrame(app.graph, platform, modifier) { AppContent(app) }
}

/**
 * The theme, image loading and adaptive layout every screen of the app sits in, with [platform] provided to the
 * screens and the dialogs and sheets drawn over all of them ([OverlayHostBox]). The arrow keys move focus as
 * a D-pad does ([arrowKeyFocus]); focus shows after a key press, not after a click or a tap ([FocusVisibility]; on
 * phones, tablets and desktop it starts hidden until the first key, TV shows it from the start). The harness name `app`
 * is the place [modifier] leaves the app: on Android the window's safe area.
 */
@Composable
internal fun TortugaFrame(graph: AppGraph, platform: TortugaPlatform, modifier: Modifier, content: @Composable () -> Unit) {
    val focusVisibility = remember { FocusVisibility(initial = platform.formFactor == FormFactor.TV) }
    // The interface language: the strings are read as the screens compose, so a new language composes them all again;
    // Arabic lays out right to left whether the system or the profile's setting chose it.
    val language by InterfaceLocale.tag.collectAsState()
    val direction = if (InterfaceLocale.isRightToLeft(language)) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalTortugaPlatform provides platform,
        LocalFocusVisibility provides focusVisibility,
        LocalLayoutDirection provides direction,
    ) {
        key(language) {
            TortugaTheme {
                ProvideImageLoader(graph.http)
                AdaptiveLayout(platform.formFactor, modifier.trackFocusVisibility(focusVisibility).arrowKeyFocus()) {
                    OverlayHostBox(Modifier.fillMaxSize().layoutProbe("app", reportsFocus = false)) { content() }
                }
            }
        }
    }
}

/**
 * The running profile's app: the adaptive navigation frame (with the profile and the sync status at the bottom of the
 * rail) and the shown destination's back stack of [app] as [BackStackLayout] lays it out: its screens in the navigation
 * container, each drawn by its screen ([RouteContent]), and the sheet over them ([SheetContent]), with the app's
 * overlay over it all; a hand-off to a player app shows its line over everything, the sheet and the overlay included
 * ([PlayerAppBannerLine]). Picking a destination shows its stack as it was left, or pops the shown one to its root
 * ([TortugaNavigator.pick]).
 *
 * Every screen but the player paints the theme background; the player stays transparent, so the platform's video
 * surface under the window content shows through (mpv's window on Windows). Back pops the stack (Android back, Esc on
 * desktop; an open sheet first), and at the root of a destination other than Home shows Home's stack; at Home's root
 * it is left to the platform.
 */
@Composable
internal fun AppContent(app: AppSession) {
    val navigator = app.navigator
    val stacks by navigator.stacks.collectAsState()
    val backStack = stacks.current
    val layout = remember(backStack) { BackStackLayout.of(backStack) }
    val state by app.state.collectAsState()
    // The chrome follows the screen on view: the one under a sheet.
    val screen = layout.screens.last()
    // The screen focus may enter (focusGate): the top one while no sheet covers it, read as focus moves.
    val focusScreen by rememberUpdatedState(screen.takeIf { layout.sheet == null })
    val compact = LocalWindowLayout.current.navigation == NavigationKind.BAR
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val status = app.gateState?.collectAsState()?.value
    // Posters, on every screen and sheet, come from the profile's template where it has one.
    CompositionLocalProvider(LocalPosterTemplate provides state.posterTemplate) {
        Box(Modifier.fillMaxSize()) {
            // The navigation container hands Back on only above a stack's root.
            SystemBackHandler(enabled = backStack.size == 1 && stacks.shown != TopLevelDestination.BOARD) { navigator.back() }
            NavigationScaffold(
                destinations = TopLevelDestination.entries,
                selected = TopLevelDestination.selectedIn(backStack),
                onSelect = navigator::pick,
                // Phones keep the bar to top-level screens (Watch together's lobby is none: Home's shortcut pushes it);
                // wider windows keep the rail beside every screen.
                showNavigation = screen != Route.Player && (!compact || TopLevelDestination.of(screen) != null),
                // While an overlay covers the screens, D-pad and Tab moves stay in it.
                modifier = if (state.overlay != null) Modifier.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup() else Modifier,
                footer = { expanded -> ProfileButton(app, expanded = expanded) },
            ) {
                // Phones have no rail to show the sync status in: a line above the screen while changes can't sync.
                val syncLine = status?.takeIf { compact && screen != Route.Player && it.syncKind.needsAttention }
                Column(Modifier.fillMaxSize()) {
                    if (syncLine != null) SyncLine(syncLine.syncStatus, syncLine.syncKind, Modifier.padding(top = statusBarOverlap))
                    // Under the sync line the screen starts below the status bar.
                    CompositionLocalProvider(LocalStatusBarOverlap provides if (syncLine != null) null else LocalStatusBarOverlap.current) {
                        // A pushed screen is drawn over the one under it, a popped one over the one it returns to. TV boxes
                        // animate that one layer (the pushed screen fades in, the popped one fades out); elsewhere the two
                        // cross-fade. The player opens at once ([PlayerOpensAtOnce]).
                        Box(Modifier.weight(1f)) {
                            NavDisplay(
                                backStack = layout.screens,
                                modifier = Modifier.fillMaxSize(),
                                onBack = { navigator.back() },
                                entryDecorators = listOf(rememberScreenStates(navigator)),
                                transitionSpec = { if (tv) fadeIn(tween(SCREEN_FADE_IN_MILLIS)) togetherWith ExitTransition.None else fadeIn(tween(SCREEN_FADE_IN_MILLIS)) togetherWith fadeOut(tween(180)) },
                                popTransitionSpec = { if (tv) EnterTransition.None togetherWith fadeOut(tween(180)) else fadeIn(tween(SCREEN_FADE_IN_MILLIS)) togetherWith fadeOut(tween(180)) },
                                predictivePopTransitionSpec = { fadeIn(tween(SCREEN_FADE_IN_MILLIS)) togetherWith fadeOut(tween(180)) },
                                entryProvider = { route ->
                                    val focusGate = Modifier.focusGate { focusScreen == route }
                                    if (route == Route.Player) {
                                        NavEntry(route, contentKey = screenKey(route), metadata = PlayerOpensAtOnce) { Box(focusGate) { RouteContent(app, route) } }
                                    } else {
                                        NavEntry(route, contentKey = screenKey(route)) {
                                            // Focus that drops on the screen goes back into it (the player has a home of its own).
                                            val screenFocus = remember { ScreenFocus { focusScreen == route } }
                                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(screenFocus.modifier).then(focusGate)) {
                                                CompositionLocalProvider(LocalScreenFocus provides screenFocus) { RouteContent(app, route) }
                                            }
                                        }
                                    }
                                },
                            )
                            // What Keep synced queued on its own and what came of a card menu's entry that couldn't be
                            // carried out, over any screen but the player, above the navigation bar.
                            if (screen != Route.Player && (state.keepSyncedNotice != null || state.menuNotice != null)) {
                                Column(
                                    Modifier.align(Alignment.BottomCenter).padding(bottom = LocalTortugaDimens.current.sectionSpacing),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    state.menuNotice?.let { TimedToast(it, "app.menuNotice", app::dismissMenuNotice) }
                                    state.keepSyncedNotice?.let { TimedToast(it, "app.keepSyncedNotice", app::dismissKeepSyncedNotice) }
                                }
                            }
                        }
                    }
                }
            }
            SheetContent(app, layout.sheet)
            // The trackers' watchlists for a title, from Detail or a card's menu, over any screen but the player.
            state.watchlist?.takeIf { screen != Route.Player }?.let { WatchlistSheet(it, app::toggleWatchlist, app::dismissWatchlist) }
            when (val overlay = state.overlay) {
                is AppOverlay.EditProfile -> OverlayPage { ProfileEditorRoute(graph = app.graph, profileId = overlay.profileId, onClose = app::closeOverlay) }
                AppOverlay.LinkIdentity -> OverlayPage { IdentityLinkRoute(app) }
                is AppOverlay.OpenOnPhone -> OpenOnPhoneOverlay(overlay, app::closeOverlay)
                AppOverlay.ParentPin -> OverlayPage { ParentPinRoute(app) }
                null -> Unit
            }
            state.playerApp?.let { PlayerAppBannerLine(it, app::playInTortuga, app::dismissPlayerApp) }
        }
    }
}

/**
 * A link nothing on this device opens ([AppOverlay.OpenOnPhone]): its QR code for a phone's camera, what to do with it,
 * the site it leads to (never the link itself: it can carry an addon's keys) and Close, which has focus. On TV and wide
 * landscape windows the QR code stands beside the text, so the dialog fits a TV's height. Harness names: `openOnPhone`
 * (the dialog), `openOnPhone.qr`, `openOnPhone.site`, `openOnPhone.close`.
 */
@Composable
private fun OpenOnPhoneOverlay(overlay: AppOverlay.OpenOnPhone, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    val beside = qrBeside()
    val text = @Composable {
        Text(overlay.message, style = MaterialTheme.typography.bodyLarge)
        overlay.site?.let {
            Text(
                it,
                Modifier.layoutProbe("openOnPhone.site"),
                style = MaterialTheme.typography.titleMedium.contentDirection(),
                color = TortugaColors.TextMuted,
            )
        }
    }
    val closeButton = @Composable {
        TortugaButton(overlay.closeLabel, onClose, Modifier.focusRequester(close).layoutProbe("openOnPhone.close"), style = ButtonStyle.PRIMARY)
    }
    ModalOverlay(overlay.title, onClose, Modifier.layoutProbe("openOnPhone"), initialFocus = close, maxWidth = if (beside) 760.dp else 560.dp) {
        if (beside) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                QrTile(overlay.url, overlay.qrDescription, "openOnPhone.qr")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    text()
                    closeButton()
                }
            }
        } else {
            text()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrTile(overlay.url, overlay.qrDescription, "openOnPhone.qr") }
            OverlayActions { closeButton() }
        }
    }
}

/** How long a screen takes to fade in. */
internal const val SCREEN_FADE_IN_MILLIS = 220

/** The player's entry: on every device it replaces the screen it opens over at once, without a fade. */
private val PlayerOpensAtOnce = NavDisplay.transitionSpec { EnterTransition.None togetherWith ExitTransition.None }

/**
 * Each screen's saved state (what it keeps with `rememberSaveable`: its scroll position, its focus memory, Library's
 * section, Search's field), kept while its route is on a stack, forgotten once the route has left every stack
 * ([TortugaNavigator.addRemovalListener]): a destination shown again finds its screens as they were.
 */
@Composable
private fun rememberScreenStates(navigator: TortugaNavigator): NavEntryDecorator<Route> {
    val states = rememberSaveableStateHolder()
    DisposableEffect(navigator, states) {
        val forget: (Route) -> Unit = { states.removeState(screenKey(it)) }
        navigator.addRemovalListener(forget)
        onDispose { navigator.removeRemovalListener(forget) }
    }
    return remember(states) {
        NavEntryDecorator(decorate = { entry -> states.SaveableStateProvider(entry.contentKey) { entry.Content() } })
    }
}

/** A screen's key for its saved state ([rememberScreenStates]): its route's text, one per route, which Android saves. */
private fun screenKey(route: Route): String = route.toString()

/**
 * The back stack's [sheet] over the screen under it; once the sheet's route has left the stack (Back, or a stream
 * picked: the player took its place) the sheet slides away. A sheet that replaces another shows at once.
 */
@Composable
private fun SheetContent(app: AppSession, sheet: Route?) {
    SheetSlot(sheet) { route, visible ->
        when (route) {
            is Route.Streams -> StreamsSheet(app, route, visible)
            else -> Unit
        }
    }
}

/**
 * [content] of the sheet shown last, with whether it still is [sheet] (false: it closed and slides away). Each time a
 * sheet opens its content is composed afresh, the same route opened again too: what it remembered belongs to the
 * appearance that closed (a route's stores are released once it leaves the back stack).
 */
@Composable
internal fun <T : Any> SheetSlot(sheet: T?, content: @Composable (sheet: T, visible: Boolean) -> Unit) {
    // A change of [sheet] recomposes this, so plain holders are enough: the sheet shown last, and its appearance.
    val last = remember { SheetAppearance<T>() }
    if (sheet != null && (!last.open || sheet != last.sheet)) {
        last.sheet = sheet
        last.count++
    }
    last.open = sheet != null
    val shown = last.sheet ?: return
    key(last.count) { content(shown, sheet == shown) }
}

private class SheetAppearance<T : Any> {
    var sheet: T? = null
    var open = false
    var count = 0
}

/** The screen of [route] in [app]. */
@Composable
private fun RouteContent(app: AppSession, route: Route) {
    when (route) {
        Route.Board -> BoardRoute(app)
        Route.Discover -> DiscoverRoute(app)
        Route.Search -> SearchRoute(app)
        is Route.Detail -> DetailRoute(app, route)
        // Drawn as a screen only when it is alone on the stack; otherwise as a sheet (SheetContent).
        is Route.Streams -> StreamsRoute(app, route)
        Route.Player -> PlayerRoute(app)
        Route.Library -> LibraryRoute(app)
        Route.Settings -> SettingsRoute(app)
        Route.WatchTogether -> WatchRoute(app)
        Route.KeepSynced -> KeepSyncedRoute(app)
        Route.Guide -> GuideRoute(app)
        Route.Calendar -> CalendarRoute(app)
        is Route.CollectionFolder -> CollectionFolderRoute(app, route)
    }
}

/**
 * The running profile's avatar at the foot of the rail and the TV column, a button that opens the profile picker where
 * the account has one, with the sync status as a small mark on the avatar's corner while changes sync or can't (muted;
 * in the error colour while they can't), its text the mark's description, which TalkBack speaks as sync trouble starts
 * and as it changes, wherever its focus is; [expanded]: the name beside it (the open TV column). Phones, which have
 * neither, switch profiles from Settings' profile card and show only sync trouble, as the [SyncLine] above the screen.
 * Harness names: `nav.profile`, `nav.sync`.
 */
@Composable
private fun ProfileButton(app: AppSession, modifier: Modifier = Modifier, expanded: Boolean = false) {
    val current = app.state.collectAsState().value.profile ?: return
    val status = app.gateState?.collectAsState()?.value
    Box(modifier) {
        TortugaSurface(
            onClick = app::switchProfile,
            enabled = app.canSwitchProfile,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier
                .layoutProbe("nav.profile")
                .semantics { if (app.canSwitchProfile) contentDescription = T.profiles.switchProfile },
        ) {
            Row(
                Modifier.padding(AvatarInset).then(if (expanded) Modifier.padding(end = 12.dp) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProfileAvatarTile(current.initial, current.avatar, size = AvatarSize)
                if (expanded) {
                    Text(current.name, style = MaterialTheme.typography.titleSmall.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (status != null && status.syncKind != SyncStatus.Kind.Idle) {
            // Over the avatar's lower trailing corner, half its side, outside the button's circle (which would cut it).
            Box(
                Modifier
                    .offset(x = AvatarInset + AvatarSize - SyncMarkSize + SyncMarkOverhang, y = AvatarInset + AvatarSize - SyncMarkSize + SyncMarkOverhang)
                    .size(SyncMarkSize)
                    .background(TortugaColors.Background, CircleShape)
                    .layoutProbe("nav.sync")
                    .semantics(mergeDescendants = true) {
                        contentDescription = status.syncStatus
                        if (status.syncKind.needsAttention) liveRegion = LiveRegionMode.Polite
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    status.syncKind.icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (status.syncKind.needsAttention) TortugaColors.Error else TortugaColors.TextMuted,
                )
            }
        }
    }
}

private val AvatarSize = 40.dp
private val SyncMarkSize = 20.dp

/** Room round the avatar inside the button's circle. */
private val AvatarInset = 4.dp

/** How far the sync mark reaches past the avatar's edge. */
private val SyncMarkOverhang = 4.dp

/**
 * The sync status as a line across the top of a phone's screen, shown while changes can't sync. TalkBack speaks it as
 * it shows and as it changes, wherever its focus is.
 */
@Composable
internal fun SyncLine(text: String, kind: SyncStatus.Kind, modifier: Modifier = Modifier) {
    Surface(color = TortugaColors.SurfaceHigh, modifier = Modifier.fillMaxWidth().layoutProbe("nav.sync")) {
        Row(
            modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(kind.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = TortugaColors.Error)
            Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = TortugaColors.Text)
        }
    }
}

private val SyncStatus.Kind.icon: ImageVector
    get() = when (this) {
        SyncStatus.Kind.Idle -> TortugaIcons.CloudDone
        SyncStatus.Kind.Syncing -> TortugaIcons.Sync
        is SyncStatus.Kind.Error -> TortugaIcons.SyncProblem
        SyncStatus.Kind.Offline, SyncStatus.Kind.SignedOut -> TortugaIcons.CloudOff
    }
