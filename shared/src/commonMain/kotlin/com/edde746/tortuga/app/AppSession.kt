package com.edde746.tortuga.app

import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.kids.isKidsProfile
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.net.WebUrl
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppBanner
import com.edde746.tortuga.playback.players.PlayerAppCatalog
import com.edde746.tortuga.playback.players.PlayerAppHandoff
import com.edde746.tortuga.playback.players.PlayerAppHandoffHost
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.suggest.StreamPicks
import com.edde746.tortuga.presentation.account.AccountGate
import com.edde746.tortuga.presentation.account.AccountGateUiState
import com.edde746.tortuga.presentation.account.IdentityLinkHost
import com.edde746.tortuga.presentation.account.IdentityLinkStore
import com.edde746.tortuga.presentation.account.identityLinkStore
import com.edde746.tortuga.presentation.addons.AddonsHost
import com.edde746.tortuga.presentation.addons.AddonsStore
import com.edde746.tortuga.presentation.addons.addonsStore
import com.edde746.tortuga.presentation.board.BoardHost
import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.board.boardStore
import com.edde746.tortuga.presentation.board.PosterTemplate
import com.edde746.tortuga.presentation.calendar.CalendarHost
import com.edde746.tortuga.presentation.calendar.CalendarStore
import com.edde746.tortuga.presentation.calendar.calendarStore
import com.edde746.tortuga.presentation.collections.FolderHost
import com.edde746.tortuga.presentation.collections.FolderStore
import com.edde746.tortuga.presentation.collections.folderStore
import com.edde746.tortuga.presentation.detail.DetailHost
import com.edde746.tortuga.presentation.detail.DetailStore
import com.edde746.tortuga.presentation.detail.detailStore
import com.edde746.tortuga.presentation.discover.DiscoverHost
import com.edde746.tortuga.presentation.discover.DiscoverStore
import com.edde746.tortuga.presentation.discover.discoverStore
import com.edde746.tortuga.presentation.downloads.DownloadsHost
import com.edde746.tortuga.presentation.downloads.DownloadsStore
import com.edde746.tortuga.presentation.downloads.KeepSyncedStore
import com.edde746.tortuga.presentation.downloads.downloadsStore
import com.edde746.tortuga.presentation.downloads.keepSyncedStore
import com.edde746.tortuga.presentation.downloads.noticeText
import com.edde746.tortuga.presentation.guide.GuideHost
import com.edde746.tortuga.presentation.guide.GuideStore
import com.edde746.tortuga.presentation.guide.guideStore
import com.edde746.tortuga.presentation.homescreen.HomeScreenLink
import com.edde746.tortuga.presentation.homescreen.homeScreenItems
import com.edde746.tortuga.presentation.homescreen.passing
import com.edde746.tortuga.presentation.library.LibraryHost
import com.edde746.tortuga.presentation.library.LibraryStore
import com.edde746.tortuga.presentation.library.continueWatchingStore
import com.edde746.tortuga.presentation.library.libraryStore
import com.edde746.tortuga.presentation.menu.WatchlistSheet
import com.edde746.tortuga.presentation.menu.watchlistSheet
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.navigation.LibrarySection
import com.edde746.tortuga.presentation.navigation.NavigationStacks
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.SavedNavigation
import com.edde746.tortuga.presentation.navigation.SavedStacks
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.navigation.TortugaNavigator
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.presentation.profiles.ParentPinStore
import com.edde746.tortuga.presentation.profiles.PinThrottle
import com.edde746.tortuga.presentation.profiles.ProfileAvatar
import com.edde746.tortuga.presentation.profiles.ProfileAvatars
import com.edde746.tortuga.presentation.profiles.profileInitial
import com.edde746.tortuga.presentation.search.SearchHost
import com.edde746.tortuga.presentation.search.SearchStore
import com.edde746.tortuga.presentation.search.searchStore
import com.edde746.tortuga.presentation.settings.SettingsSection
import com.edde746.tortuga.presentation.streams.StreamsHost
import com.edde746.tortuga.presentation.streams.StreamsStore
import com.edde746.tortuga.presentation.watch.RoomPlayerWatch
import com.edde746.tortuga.presentation.watch.WatchStore
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.WatchlistLine
import com.edde746.tortuga.trackers.firstMeta
import com.edde746.tortuga.watch.RoomPlayback
import com.edde746.tortuga.watch.WatchPlaybackHost
import com.edde746.tortuga.watch.WatchTogether
import com.edde746.tortuga.watch.watchTogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What can cover the running app's screens ([AppSessionState.overlay]). */
sealed interface AppOverlay {
    /** The profile editor of [profileId]; null: a new profile's (a development session opens it). */
    data class EditProfile(val profileId: String?) : AppOverlay

    /** "Add a sign-in method" (the account settings). */
    data object LinkIdentity : AppOverlay

    /**
     * A link nothing on this device opens ([AppSession.openUrl]: an Apple TV has no browser, an Android TV may have
     * none): [url] as a QR code to open on a phone, with [message] saying so and [site] (the link's host, null for a
     * link without one) saying where it leads. The link itself isn't written out: an addon's configuration page
     * carries its keys.
     */
    data class OpenOnPhone(
        val url: String,
        val title: String,
        val message: String,
        val site: String?,
        val qrDescription: String,
        val closeLabel: String,
    ) : AppOverlay

    /**
     * A parent's PIN, asked on a kids profile before what it guards ([AppSession.askParent]); the UI renders
     * [AppSession.parentPinStore]'s state.
     */
    data object ParentPin : AppOverlay
}

/**
 * What the running app shows beside its back stack.
 *
 * @param playback the playback the player shows, or null.
 * @param overlay what covers the screens (the profile editor, adding a sign-in method), or null.
 * @param playerApp a hand-off to a player app under way or failed: one line over the screen on top (the Streams sheet
 *   included), with its actions ([playInTortuga], [dismissPlayerApp]); null otherwise.
 * @param installPreview where links preview over the screen on top ([AppPlatform.installLinksOverScreen]): the transport
 *   URL of the addon a followed `stremio://…/manifest.json` link brought, its install page shown over whatever shows
 *   until [AppSession.closeInstallPreview]; null otherwise.
 * @param keepSyncedNotice what the profile's Keep synced rules queued on their own ("Keep synced added 2 episodes of
 *   Breaking Bad"): one line over the screens but the player, then [AppSession.dismissKeepSyncedNotice]; null otherwise.
 * @param menuNotice what came of a card menu's entry that couldn't be carried out, on whichever screen it was picked
 *   ("Couldn't mark Breaking Bad: …"): one line over the screens but the player, then [AppSession.dismissMenuNotice];
 *   null otherwise.
 * @param watchlist the Watchlist sheet of the title a card's menu or Detail opened it on: its place on each of the
 *   profile's tracker lists, toggled with [AppSession.toggleWatchlist], over the screens but the player until
 *   [AppSession.dismissWatchlist]; null otherwise.
 * @param profile the running profile as the navigation's profile button shows it; null until it is read.
 * @param posterTemplate where the screens' posters come from in place of the addons' ([PosterTemplate.url]); null for
 *   the addons' own: no template set, or a kids profile ([isKidsProfile]), which ignores it.
 */
data class AppSessionState(
    val playback: ActivePlayback?,
    val overlay: AppOverlay?,
    val playerApp: PlayerAppBanner? = null,
    val installPreview: TransportUrl? = null,
    val keepSyncedNotice: String? = null,
    val menuNotice: String? = null,
    val watchlist: WatchlistSheet? = null,
    val profile: RunningProfile? = null,
    val posterTemplate: PosterTemplate? = null,
)

/**
 * The running profile as the app's navigation shows it: its [name], its avatar's letter ([initial], [profileInitial])
 * and colours ([avatar], [ProfileAvatars.forProfile]).
 */
data class RunningProfile(val name: String, val initial: String, val avatar: ProfileAvatar)

/** The account a running profile belongs to, from the account shell ([RootSession]). */
internal class ProfileAccount(val account: SyncAccount, val sync: SyncEngine, val signIn: SignInPlatform, val gate: AccountGate)

/**
 * One running app for one profile: the shared [graph], the [navigator] (the back stack each UI renders), the
 * [playback]s, what covers the screens ([state]), and the stores of the screens, whose hosts lead back here (an item
 * opens its Detail screen, a stream plays, a link is followed).
 *
 * The account shell ([RootSession]) creates one when a profile starts running and [close]s it when the profile is left
 * (a profile switch, a sign-out), so [profileId] and every store built for it are fixed for the
 * session's lifetime. A development session (the dev module) creates one without an account. Main thread only.
 *
 * @param start the first screen; null for the stacks [saved] restores, else the Board.
 * @param saved the stacks the profile's last session on this device left ([SavedNavigation]), read by the caller, as
 *   the session starts after the profile's PIN; null for none.
 * @param localSignIn the sign-in platform of an app without an account (no [profileAccount]): its tracker sign-ins'
 *   own browser.
 * @param startProfile the running profile as read when the session starts (the account gate's last read, a
 *   development session's start), so the stacks [saved] restores and what opens at once are checked against it; null
 *   when it isn't read, which counts as a kids profile until the session reads it ([isKidsProfile]).
 */
class AppSession internal constructor(
    val graph: AppGraph,
    val profileId: String,
    private val platform: AppPlatform,
    start: Route?,
    private val profileAccount: ProfileAccount?,
    private val localSignIn: SignInPlatform? = null,
    saved: SavedStacks? = null,
    startProfile: Profile? = null,
) {
    /** An app without an account (a development session's) running [profile], read as it starts: no sync, no profile picker. */
    constructor(graph: AppGraph, profile: Profile, platform: AppPlatform, start: Route?, signIn: SignInPlatform?, saved: SavedStacks?) :
        this(graph, profile.id, platform, start, profileAccount = null, localSignIn = signIn, saved = saved, startProfile = profile)

    private val scope = CoroutineScope(graph.scope.coroutineContext + SupervisorJob(graph.scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(AppSessionState(playback = null, overlay = null))
    private var closed = false

    /** The lines the [AppSessionState.watchlist] sheet shows, for [toggleWatchlist]. */
    private var watchlistLines: List<WatchlistLine> = emptyList()

    /** The playback and the overlay. */
    val state: StateFlow<AppSessionState> = mutableState.asStateFlow()

    /**
     * The running profile: as read when the session started ([startProfile]), then as stored; null until either has
     * read it. A profile deleted meanwhile stays what it was until the session ends.
     */
    private val profile = MutableStateFlow(startProfile)

    /**
     * The session runs a kids profile ([isKidsProfile]: the profile is one, or isn't
     * read yet): no profile switch or Settings' managing without a parent's PIN ([askParent]), no editing the profile,
     * no addon install or device link or Watch together (in the player neither: [wireWatchTogether]), no TV guide
     * ([offers]), no web links out ([openUrl]), no player apps, and Settings cut to the display, language, subtitles
     * and playback.
     */
    internal val kidsActive: Boolean get() = isKidsProfile(profile.value)

    /** The running profile as the session has it now ([profile]): what a store of its screens starts from. */
    internal val currentProfile: Profile? get() = profile.value

    /**
     * The back stacks, one per destination; a destination's root keeps its store until the session closes. They start
     * on [start], else on what [saved] restores of the screens this app has ([offers]), else on the Board.
     */
    val navigator = TortugaNavigator(
        start?.let(NavigationStacks::of) ?: saved?.restore(::offers) ?: NavigationStacks.of(Route.Board),
    )

    /**
     * Whether this app has [route]'s screen: the TV guide where the profile isn't a kids profile ([kidsActive]), Keep
     * synced where this device has downloads.
     */
    private fun offers(route: Route): Boolean = when (route) {
        Route.Guide -> !kidsActive
        Route.KeepSynced -> graph.downloads != null
        else -> true
    }

    /** The profile's hand picks of streams on this device: the Streams list's and the player's ([StreamPicks]). */
    private val streamPicks: StreamPicks = graph.streamPicks(profileId, platform)

    val playback = PlaybackController(graph, profileId, platform, streamPicks, navigator, scope) { active ->
        mutableState.update { it.copy(playback = active) }
    }

    /** Watch together for this profile: the room this device is in, its player side. */
    internal val watchTogether: WatchTogether = graph.watchTogether(profileId, RoomHost())

    /**
     * Library's sections, its tabs in order: Library, Continue Watching and, where this device has downloads
     * ([AppGraph.downloads]), Downloads.
     */
    val librarySections: List<LibrarySection> =
        if (graph.downloads != null) LibrarySection.entries else LibrarySection.entries - LibrarySection.DOWNLOADS

    /** The signed-in account (settings: account, devices, deletion); null in a development session. */
    val account: SyncAccount? get() = profileAccount?.account

    /** The account's sync engine (settings: the sync status, "Sync now"); null in a development session. */
    val sync: SyncEngine? get() = profileAccount?.sync

    /** What this device can do to sign in: a tracker sign-in's own browser and its return. */
    internal val signIn: SignInPlatform? get() = profileAccount?.signIn ?: localSignIn

    /** The account gate's state, for the sync status line; null in a development session. */
    val gateState: StateFlow<AccountGateUiState>? get() = profileAccount?.gate?.state

    /** [switchProfile] can go to the profile picker: false in a development session, which has no picker. */
    val canSwitchProfile: Boolean get() = profileAccount != null

    private val hosts = Hosts()

    /** What a parent's PIN guards while [AppOverlay.ParentPin] shows. */
    private var parentCheck: PendingParentCheck? = null

    /** The wall clock PIN lockouts count in: the account gate's (a development session, which has none: the graph's). */
    private val pinClock: () -> Long = profileAccount?.gate?.clock ?: { graph.clock.now().toEpochMilliseconds() }

    /** The PIN pads' wrong PINs on this device, kept across launches: the gate's, which the profile picker counts in too. */
    private val parentPins = profileAccount?.gate?.pinThrottle ?: PinThrottle(graph.data.deviceSettings, pinClock)

    /** This device's player apps, where the platform has them ([AppPlatform.playerApps]). */
    internal val playerApps: PlayerAppCatalog? = platform.playerApps?.let { graph.playerAppCatalog(it, scope) }

    /** The hand-offs to [playerApps] that [playback] makes instead of opening the player. */
    internal val playerAppHandoff: PlayerAppHandoff? = playerApps?.let { graph.playerAppHandoff(profileId, it, PlayerAppHost()) }

    init {
        // The profile's interface language, as it is stored and as another device changes it (null: the system's).
        scope.launch { graph.data.settings.observe(profileId).map { it.interfaceLanguage }.distinctUntilChanged().collect(InterfaceLocale::setChosen) }
        // Continue Watching holds to the library's rule for states written without their meta: a sync from another
        // device's older build, a file import, a state an older build left here (LibraryRepository.settleContinueWatching).
        scope.launch { graph.data.library.settleContinueWatching(profileId) { type, id -> graph.firstMeta(profileId, type, id) } }
        // The profile's addons' manifests, fetched again a while after each return to the foreground, as an addon
        // reconfigured on its site or updated by its author serves them now.
        scope.refreshManifestsInForeground(graph, profileId)
        // The TV's home screen shows this profile's Continue Watching as it changes, a series a new episode brings back
        // included, and keeps it once the app is gone; a kids profile's, only the titles its gate lets through.
        platform.homeScreen?.let { home ->
            scope.launch { graph.homeScreenItems(profileId).passing(graph.kidsGate(profileId)).collect(home::publish) }
        }
        // Leaving the player (Back, Close, another screen replacing it) ends the playback.
        navigator.addRemovalListener { if (it == Route.Player) playback.stop() }
        // The stacks, kept on this device as they change for the profile's next session.
        scope.launch {
            navigator.stacks.map(SavedStacks::of).distinctUntilChanged().collect { graph.data.deviceSettings.set(SavedNavigation(profileId), it) }
        }
        playback.handoff = playerAppHandoff.takeUnless { kidsActive }
        scope.launch {
            graph.data.profiles.observeProfile(profileId).collect { stored ->
                if (stored == null) return@collect
                profile.value = stored
                mutableState.update { it.copy(profile = RunningProfile(stored.name, profileInitial(stored.name), ProfileAvatars.forProfile(stored.avatar, stored.id))) }
                // A kids profile plays in Tortuga's player only: the stream's link doesn't leave the app.
                playback.handoff = playerAppHandoff.takeUnless { kidsActive }
            }
        }
        wireWatchTogether()
        graph.downloads?.let { manager ->
            scope.launch { manager.keepSyncedNotice(profileId).collect { notice -> mutableState.update { it.copy(keepSyncedNotice = notice?.noticeText()) } } }
        }
        scope.launch { graph.menuNotices.map { it[profileId] }.distinctUntilChanged().collect { notice -> mutableState.update { it.copy(menuNotice = notice) } } }
        scope.launch {
            combine(graph.data.settings.observe(profileId).map { it.posterTemplate }, profile.map(::isKidsProfile)) { template, kids -> PosterTemplate.of(template, kids) }
                .distinctUntilChanged()
                .collect { template -> mutableState.update { it.copy(posterTemplate = template) } }
        }
        followWatchlistSheet()
    }

    /**
     * Back to the profile picker, to switch or manage profiles; this session is closed once another profile runs. On a
     * kids profile a parent's PIN comes first ([ParentCheck.Leave]).
     */
    fun switchProfile() = toPicker(ParentCheck.Leave)

    /** Settings' "Switch or manage profiles": the picker, after a parent's PIN on a kids profile ([ParentCheck.Settings]). */
    internal fun manageProfiles() = toPicker(ParentCheck.Settings)

    private fun toPicker(check: ParentCheck) {
        val gate = profileAccount?.gate ?: return
        if (kidsActive) askParent(check) { gate.switchProfile() } else gate.switchProfile()
    }

    /**
     * Opens the editor of [profileId] (null: a new profile) over the app; the caller has unlocked it (the running
     * profile is). Nothing on a kids profile ([kidsActive]): its editing is a parent's, from another profile.
     */
    fun editProfile(profileId: String?) {
        if (kidsActive) return Log.i(TAG) { "Not opening the profile editor on a kids profile" }
        mutableState.update { it.copy(overlay = AppOverlay.EditProfile(profileId)) }
    }

    /** Opens "add a sign-in method" (another provider for the account) over the app. */
    fun linkIdentity() {
        if (profileAccount != null) mutableState.update { it.copy(overlay = AppOverlay.LinkIdentity) }
    }

    /**
     * The account was deleted from this device's settings: the sign-in screen the app goes to shows [message] (what
     * `SyncAccount.deleteAccount` returned) instead of the plain "deleted" notice.
     */
    fun accountDeleted(message: String) {
        profileAccount?.gate?.showAccountDeleted(message)
    }

    /** Closes the overlay; a parent's PIN that was asked ([AppOverlay.ParentPin]) is given up. */
    fun closeOverlay() {
        parentCheck = null
        mutableState.update { it.copy(overlay = null) }
    }

    /**
     * Asks a parent's PIN ([AppOverlay.ParentPin], [parentPinStore]) before [onUnlocked] runs; the PIN of any profile
     * on the account that isn't a kids profile unlocks it. Cancelled, nothing runs. A later ask replaces this one.
     */
    internal fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) {
        parentCheck = PendingParentCheck(check, onUnlocked)
        mutableState.update { it.copy(overlay = AppOverlay.ParentPin) }
    }

    /** The parent's PIN pad while [AppOverlay.ParentPin] shows; the UI releases it when the overlay goes. */
    fun parentPinStore(): ParentPinStore {
        val pending = checkNotNull(parentCheck) { "no parent's PIN is asked (AppOverlay.ParentPin isn't shown)" }
        return ParentPinStore(
            check = pending.check,
            profiles = graph.data.profiles,
            throttle = parentPins,
            clock = pinClock,
            unlocked = {
                if (parentCheck === pending) {
                    closeOverlay()
                    scope.launch { pending.onUnlocked() }
                }
            },
            cancelled = { if (parentCheck === pending) closeOverlay() },
            parentScope = scope,
        )
    }

    /** Closes the install page a followed link showed over the screen ([AppSessionState.installPreview]). */
    fun closeInstallPreview() {
        mutableState.update { it.copy(installPreview = null) }
    }

    /**
     * Follows a `stremio:` link inside the app. An addon's install link opens Settings' Addons section on its install
     * page ([openAddons]), or where the platform says so ([AppPlatform.installLinksOverScreen]) shows that page over the
     * screen on top; on a kids profile ([kidsActive]) it opens nothing.
     */
    fun openLink(link: DeepLink) {
        when (link) {
            is DeepLink.Detail -> navigator.navigate(
                Route.Detail(
                    link.type, link.id, link.videoId,
                    videoAction = if (link.autoPlay && link.videoId != null) DetailVideoAction.AUTO_PLAY_LINK else DetailVideoAction.STREAMS,
                ),
            )
            is DeepLink.Discover -> navigator.openDiscover(link)
            DeepLink.DiscoverDefault -> navigator.select(TopLevelDestination.DISCOVER)
            is DeepLink.Search -> navigator.openSearch(link.query)
            DeepLink.Board -> navigator.select(TopLevelDestination.BOARD)
            DeepLink.Library -> navigator.openLibrary(LibrarySection.LIBRARY)
            is DeepLink.InstallAddon -> when {
                kidsActive -> Log.i(TAG) { "Not following an addon's install link on a kids profile" }
                platform.installLinksOverScreen -> mutableState.update { it.copy(installPreview = link.transportUrl) }
                else -> openAddons(link.transportUrl)
            }
        }
    }

    /**
     * Opens Settings on its Addons section, as the Settings tab and its Addons row do (Back leaves Settings for Home; a
     * Settings visit already there keeps its stores). With [installLink] (the transport URL of a followed
     * `stremio://…/manifest.json` link) the section shows that addon's install preview. Nothing on a kids profile, whose
     * Settings have no Addons section.
     */
    fun openAddons(installLink: TransportUrl? = null) {
        if (kidsActive) return
        navigator.select(TopLevelDestination.SETTINGS)
        checkNotNull(settingsSession()) { "Settings is on the back stack" }.openAddons(installLink)
    }

    /**
     * Follows a link the OS handed the app: a watch-together invite (`tortuga://watch?…`) opens Watch together and joins
     * its room, asking first when that leaves another room or goes through another relay than this device's (nothing
     * for the room it is in already; [WatchTogether.openInvite]); a device link's QR code (`tortuga://link?…`, scanned
     * with a camera) opens Settings → Account, which checks it ([SettingsSession.openDeviceLink]); an item of this
     * profile's on the TV's home screen ([HomeScreenLink]) closes the player left open when the app went to the
     * background (its playback ends, as on Back), then resumes its video as its Continue Watching card does, or opens its
     * title (another profile's item is [RootSession.openUrl]'s to route); a `stremio:` link goes where
     * [openLink] takes it. False for another profile's item. On a kids profile ([kidsActive]) an invite and a device link's code
     * open nothing (true: they are this app's links, refused).
     */
    fun openIncomingLink(link: IncomingLink): Boolean {
        when (link) {
            is IncomingLink.Invite -> {
                if (kidsActive) {
                    Log.i(TAG) { "Not following a watch-together invite on a kids profile" }
                    return true
                }
                openWatchTogether()
                scope.launch { watchTogether.openInvite(link.invite) }
            }
            is IncomingLink.DeviceLink -> {
                if (kidsActive) {
                    Log.i(TAG) { "Not following a device link's code on a kids profile" }
                    return true
                }
                navigator.select(TopLevelDestination.SETTINGS)
                checkNotNull(settingsSession()) { "Settings is on the back stack" }.openDeviceLink(link.qr)
            }
            is IncomingLink.HomeScreen -> {
                val item = link.item
                if (item.profileId != profileId) {
                    Log.w(TAG) { "Not opening another profile's home screen item here" }
                    return false
                }
                navigator.pop(Route.Player)
                when (item) {
                    is HomeScreenLink.Play -> hosts.openVideo(item.type, item.id, item.videoId)
                    is HomeScreenLink.Detail -> hosts.openMeta(item.type, item.id)
                }
            }
            is IncomingLink.Stremio -> openLink(link.link)
        }
        return true
    }

    /** [openIncomingLink] for a link as text, as a development session takes it; false (logged) for one this app doesn't open. */
    fun openIncomingLink(url: String): Boolean {
        val link = IncomingLink.parse(url) ?: run {
            Log.w(TAG) { "No link this app opens: $url" }
            return false
        }
        return openIncomingLink(link)
    }

    /**
     * Opens Watch together (the lobby: join a room, the room once in one) over Home, so Back returns Home. Nothing on a
     * kids profile ([kidsActive]): a room plays what its host picks.
     */
    fun openWatchTogether() {
        if (kidsActive) return
        navigator.select(TopLevelDestination.BOARD, Route.WatchTogether)
    }

    /**
     * Opens the Calendar over the screen showing, so Back returns there: Home's Calendar shortcut and Library's Calendar
     * button, where the app has the screen.
     */
    fun openCalendar() = navigator.navigate(Route.Calendar)

    /** Opens Library on Downloads (Home's, Discover's and Search's offline action); nothing where there are none. */
    fun openDownloads() {
        if (LibrarySection.DOWNLOADS in librarySections) navigator.openLibrary(LibrarySection.DOWNLOADS)
    }

    /**
     * The section Library opens on: the one asked for ([TortugaNavigator.openLibrary]), else Downloads while the device
     * is offline (where it has downloads), else the library. A Library screen calls it when it first shows in the
     * session and keeps the section shown from then on, as Library's stack keeps its stores
     * ([TortugaNavigator.retain]); it follows [TortugaNavigator.librarySection] (taking it with
     * [TortugaNavigator.takeLibrarySection]) while it shows.
     */
    fun librarySection(): LibrarySection {
        navigator.takeLibrarySection()?.takeIf { it in librarySections }?.let { return it }
        val offline = !graph.network.value.online
        return if (offline && LibrarySection.DOWNLOADS in librarySections) LibrarySection.DOWNLOADS else LibrarySection.LIBRARY
    }

    /**
     * Opens [url] outside the app: the browser, or the app that handles it. Only `http`/`https` links leave the app
     * ([WebUrl.isWeb]): anything else (an addon's `file:` or `search-ms:` link, a `stremio:` link the app doesn't route)
     * opens nothing, and only its scheme is logged. Where nothing on this device opens it (an Apple TV has no browser; an
     * Android TV may have none), it shows as a QR code to open on a phone instead ([AppOverlay.OpenOnPhone]): an addon's
     * configuration page, a stream or trailer that plays outside the app. On a kids profile ([kidsActive]) no link
     * leaves the app.
     */
    fun openUrl(url: String) {
        if (kidsActive) return Log.i(TAG) { "Not opening a link on a kids profile" }
        if (!WebUrl.isWeb(url)) return Log.w(TAG) { "Not opening a link with scheme ${WebUrl.scheme(url)}: only http(s) leave the app" }
        if (platform.openUrl(url)) return
        val text = T.nav.openOnPhone
        val site = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        mutableState.update {
            it.copy(overlay = AppOverlay.OpenOnPhone(url, text.title, text.message, site.ifEmpty { null }, text.qrDescription, T.nav.close))
        }
    }

    /** The [AppSessionState.playerApp] banner's "Play in Tortuga": the video whose hand-off failed plays in Tortuga's player. */
    fun playInTortuga() {
        playerAppHandoff?.playInTortuga()
    }

    /** The [AppSessionState.playerApp] banner's Cancel (a hand-off under way stops) or Dismiss. */
    fun dismissPlayerApp() {
        playerAppHandoff?.dismiss()
    }

    /** The [AppSessionState.keepSyncedNotice] has shown: it goes. */
    fun dismissKeepSyncedNotice() {
        graph.downloads?.dismissKeepSyncedNotice(profileId)
    }

    /** The [AppSessionState.menuNotice] has shown: it goes. */
    fun dismissMenuNotice() {
        graph.menuNotices.update { it - profileId }
    }

    /** Puts the [AppSessionState.watchlist] sheet's title on [service]'s list, or takes it off when it is on it. */
    fun toggleWatchlist(service: TrackerService) {
        val request = graph.watchlistSheets.value[profileId] ?: return
        val line = watchlistLines.firstOrNull { it.service == service } ?: return
        graph.trackers?.coordinator(profileId)?.setWatchlisted(request.type, request.id, request.meta, service, listed = line.listed != true)
    }

    /** Closes the [AppSessionState.watchlist] sheet; the writes it started go on. */
    fun dismissWatchlist() {
        graph.watchlistSheets.update { it - profileId }
    }

    /** Shows the profile's Watchlist sheet while one is open, its rows following the title's lines; none on a kids profile. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun followWatchlistSheet() {
        val coordinator = graph.trackers?.coordinator(profileId) ?: return
        scope.launch {
            graph.watchlistSheets.map { it[profileId] }.distinctUntilChanged().flatMapLatest { request ->
                if (request == null || kidsActive) flowOf(null) else coordinator.watchlist(request.type, request.id, request.meta)
            }.collect { lines ->
                watchlistLines = lines.orEmpty()
                mutableState.update { it.copy(watchlist = lines?.let(::watchlistSheet)) }
            }
        }
    }

    // ---- The stores of the screens whose way out is the app ----------------------------------------------------------

    /**
     * A TV's (Android TV, Fire TV, Apple TV) rows end in a See all card; its Watch together shortcut counts this
     * device's room, and a kids profile has none, nor the TV guide's ([kidsActive]).
     */
    fun boardStore(): BoardStore = graph.boardStore(
        profileId,
        hosts,
        remote = platform.formFactor == FormFactor.TV,
        watchingTogether = watchTogether.state.map { state -> state.party.room?.takeIf { state.inRoom }?.participants?.size ?: 0 },
        kidsProfile = profile.map(::isKidsProfile),
    )

    /** Opened on the catalog [TortugaNavigator.openDiscover] asked for, if any. */
    fun discoverStore(): DiscoverStore = graph.discoverStore(profileId, navigator.takeDiscoverLink(), hosts)

    /** Searching the query [TortugaNavigator.openSearch] asked for, if any. */
    fun searchStore(): SearchStore = graph.searchStore(profileId, navigator.takeSearchQuery(), hosts)

    /**
     * The Detail of [route], drawn from the catalog item it was opened from until the title loads, idle while a screen
     * covers it; once the title is known it does [Route.Detail.videoAction]: its video's stream list, the video playing,
     * or a card's menu entry. A kids profile's has no player apps.
     */
    fun detailStore(route: Route.Detail): DetailStore = graph.detailStore(
        profileId,
        route.type,
        route.id,
        route.videoId,
        hosts,
        playerAppHandoff.takeUnless { kidsActive },
        route.shownVideoId,
        preview = navigator.preview(route),
        covered = navigator.covered(route),
        videoAction = route.videoAction,
        sharing = platform.formFactor != FormFactor.TV,
    )

    /**
     * An Addons store: Settings' Addons section's ([SettingsSession.addons]) and, where links preview over the screen,
     * the install page's ([AppSessionState.installPreview]). Configure's pages open through [openUrl].
     */
    fun addonsStore(): AddonsStore = graph.addonsStore(profileId, hosts)

    /** The Library screen. */
    fun libraryStore(): LibraryStore = graph.libraryStore(profileId, hosts)

    /** The Continue Watching screen. */
    fun continueWatchingStore(): LibraryStore = graph.continueWatchingStore(profileId, hosts)

    /** The Calendar screen ([Route.Calendar]); the screen releases it. */
    fun calendarStore(): CalendarStore = graph.calendarStore(profileId, hosts)

    /**
     * A collection's folder ([Route.CollectionFolder]); the screen releases it. A kids profile's notes have no buttons
     * into Settings.
     */
    fun folderStore(route: Route.CollectionFolder): FolderStore =
        graph.folderStore(profileId, route.collectionId, route.folderId, hosts, kidsProfile = kidsActive)

    /**
     * Library's Downloads section ([LibrarySection.DOWNLOADS]), with "Play with…" where other apps can open the files
     * (not on a kids profile) and a row to the Keep synced screen; null where this device has no downloads.
     */
    fun downloadsStore(): DownloadsStore? =
        graph.downloadsStore(profileId, hosts, playerApps?.takeIf { it.launcher.handsOffFiles && !kidsActive }?.players)

    /** The Keep synced screen ([Route.KeepSynced]); null where this device has no downloads. */
    fun keepSyncedStore(): KeepSyncedStore? = graph.keepSyncedStore(profileId, hosts)

    /** The TV guide ([Route.Guide]); the screen releases it. */
    fun guideStore(): GuideStore = graph.guideStore(profileId, hosts)

    /**
     * The Streams screen of [route], over the item [TortugaNavigator.openStreams] opened it with (and its notice): call
     * it while the route is on the stack, which keeps the item. A kids profile's has no "Play with…".
     */
    fun streamsStore(route: Route.Streams): StreamsStore {
        val meta = checkNotNull(navigator.meta(route.type, route.id)) { "streams are opened through TortugaNavigator.openStreams" }
        val players = playerApps?.takeUnless { kidsActive }?.players
        return graph.streamsStore(profileId, meta, route.videoId, hosts, navigator.streamsNotice(route), route.download, players, route.fromStart, streamPicks)
    }

    /** The Watch together screen ([Route.WatchTogether]); the screen releases it. */
    fun watchStore(): WatchStore = WatchStore(watchTogether, watchTogether.playback, graph.data.deviceSettings, graph.clock, scope)

    /**
     * The Settings screen's stores, kept for the session once made (Settings' route stays at the bottom of its stack);
     * null until Settings first shows ([TortugaNavigator.retain]). A screen calls it once per appearance
     * of the route.
     */
    fun settingsSession(): SettingsSession? =
        navigator.retain(Route.Settings, SettingsSession.KEY, create = { SettingsSession(this, platform) }, release = SettingsSession::release)

    /** "Add a sign-in method" ([AppOverlay.LinkIdentity]): linking or leaving closes the overlay. */
    fun identityLinkStore(): IdentityLinkStore {
        val account = checkNotNull(profileAccount) { "a development session has no account" }
        return graph.identityLinkStore(account.account, account.signIn, hosts)
    }

    // ---- Lifecycle ----------------------------------------------------------------------------------------------------

    /** The picker covers the app while the user switches profile: the playback ends, the overlay closes. */
    internal fun leave() {
        closeOverlay()
        closeInstallPreview()
        if (!navigator.pop(Route.Player)) playback.stop()
    }

    /** Ends the playback, leaves the watch-together room and releases every screen's store. Later calls do nothing. */
    fun close() {
        if (closed) return
        closed = true
        closeOverlay()
        closeInstallPreview()
        playback.close()
        playerAppHandoff?.close()
        playerApps?.release()
        watchTogether.close()
        navigator.close()
        scope.cancel()
    }

    /** The room's hold on this session's playbacks. */
    private fun wireWatchTogether() {
        val room = watchTogether
        playback.room = object : PlaybackRoom {
            override val inRoom: Boolean get() = room.state.value.inRoom

            override fun beforePlay(request: PlayRequest): PlayRequest? = room.playback.beforePlay(request)

            override fun onStarted(active: ActivePlayback) {
                // A kids profile has no Watch together: its player offers no room to start and follows none.
                if (kidsActive) return
                active.store.attachWatch(RoomPlayerWatch(room, room.playback, active.watch, scope))
                room.playback.onStarted(active.watch)
            }

            override fun onStopped(active: ActivePlayback, replaced: Boolean) {
                room.playback.onStopped(active.watch, replaced)
                active.store.attachWatch(null)
            }
        }
    }

    /** The playbacks the room's player side follows, the player it opens (a guest's) and closes. */
    private inner class RoomHost : WatchPlaybackHost {
        override val active: RoomPlayback? get() = playback.active?.watch

        override fun openRoomPlayer(): RoomPlayback? = playback.openForRoom()?.watch

        override fun closePlayer() {
            if (!navigator.pop(Route.Player)) playback.stop()
        }

        override fun openStreams(meta: MetaItem, videoId: String) = navigator.openStreams(meta, videoId)
    }

    /** Where the screens' stores lead: the back stack, the player, links, the overlay, the clipboard. */
    private inner class Hosts :
        BoardHost,
        DiscoverHost,
        SearchHost,
        LibraryHost,
        DetailHost,
        StreamsHost,
        DownloadsHost,
        AddonsHost,
        GuideHost,
        IdentityLinkHost,
        CalendarHost,
        FolderHost {
        override fun openMeta(type: String, id: String) = navigator.navigate(Route.Detail(type, id))

        /** Settings → Trackers, so a folder's tracker gets connected; nothing on a kids profile. */
        override fun openTrackers() {
            if (kidsActive) return
            navigator.select(TopLevelDestination.SETTINGS)
            checkNotNull(settingsSession()) { "Settings is on the back stack" }.main.open(SettingsSection.TRACKERS)
        }

        override fun openAddons() = this@AppSession.openAddons()

        /**
         * A catalog item opens straight to a video's streams ([Route.Detail.videoId]) only when that adds something: a
         * live item to its own id's, an item whose `behaviorHints.defaultVideoId` names another video than its own id (a
         * channel's default video) to that one's. Anything else opens plain Detail, a movie whose default video is its
         * own id (every Cinemeta movie) included: its Play plays that video.
         */
        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) {
            val videoId = preview?.let { if (it.isLive) it.id else it.behaviorHints.defaultVideoId?.takeIf { video -> video != it.id } }
            navigator.openDetail(Route.Detail(type, id, videoId), preview)
        }

        /** A Continue Watching item resumes: it plays as Detail's Resume plays its video ([DetailVideoAction.PLAY]). */
        override fun openVideo(type: String, id: String, videoId: String) =
            navigator.navigate(Route.Detail(type, id, videoId, videoAction = DetailVideoAction.PLAY))

        override fun openDiscover(link: DeepLink.Discover) = navigator.openDiscover(link)

        override fun openDownloads() = this@AppSession.openDownloads()

        override fun openWatchTogether() = this@AppSession.openWatchTogether()

        override fun openLibrary() = navigator.openLibrary(LibrarySection.LIBRARY)

        /** Over Home, so Back returns Home; nothing where the app has no guide ([offers]: a kids profile's). */
        override fun openGuide() {
            if (offers(Route.Guide)) navigator.select(TopLevelDestination.BOARD, Route.Guide)
        }

        override fun playChannel(channel: MetaItemPreview) = navigator.openDetail(
            Route.Detail(channel.type, channel.id, channel.id, videoAction = DetailVideoAction.AUTO_PLAY_LINK),
            channel,
        )

        override fun openCalendar() = this@AppSession.openCalendar()

        override fun openCollectionFolder(collectionId: String, folderId: String) =
            navigator.navigate(Route.CollectionFolder(collectionId, folderId))

        /** An episode of the calendar: its series' Detail on the episode's season, the episode brought into view. */
        override fun openEpisode(type: String, id: String, videoId: String) =
            navigator.navigate(Route.Detail(type, id, shownVideoId = videoId))

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) =
            navigator.navigate(Route.Detail(type, id, videoId, videoAction = action))

        override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) =
            navigator.openStreams(meta, videoId, notice, fromStart = fromStart)

        override fun openDownloadStreams(meta: MetaItem, videoId: String) = navigator.openStreams(meta, videoId, download = true)

        override fun openKeepSynced() = navigator.navigate(Route.KeepSynced)

        override fun closeStreams() {
            if (navigator.current is Route.Streams) navigator.back()
        }

        override fun play(request: PlayRequest) = playback.play(request)

        override fun playWith(request: PlayRequest, playerId: String) = playback.play(request, playerId)

        override fun openUrl(url: String) = this@AppSession.openUrl(url)

        override fun copyToClipboard(text: String) = platform.setClipboardText(text, sensitive = true)

        override fun openDeepLink(link: DeepLink) = openLink(link)

        override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) {
            if ((navigator.current as? Route.Detail)?.id == entryId) navigator.replaceDetail(Route.Detail(type, id, shownVideoId = shownVideoId), preview)
        }

        override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = this@AppSession.askParent(check, onUnlocked)

        override fun identityLinked() = closeOverlay()

        override fun closeIdentityLink() = closeOverlay()
    }

    /** Where the hand-offs lead: the banner, the Streams sheet they close, Tortuga's player. */
    private inner class PlayerAppHost : PlayerAppHandoffHost {
        override fun playInTortuga(request: PlayRequest) = playback.play(request, PlayerApp.TORTUGA_ID)

        override fun closeStreams() = hosts.closeStreams()

        override fun showBanner(banner: PlayerAppBanner?) = mutableState.update { it.copy(playerApp = banner) }
    }
}

/** A parent's PIN asked for [check], then [onUnlocked] ([AppSession.askParent]). */
private class PendingParentCheck(val check: ParentCheck, val onUnlocked: suspend () -> Unit)

private const val TAG = "AppSession"
