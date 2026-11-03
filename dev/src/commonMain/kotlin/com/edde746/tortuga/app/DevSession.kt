package com.edde746.tortuga.app

import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AddonException
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.SavedNavigation
import com.edde746.tortuga.presentation.navigation.SavedStacks
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.profiles.ProfileAvatars
import com.edde746.tortuga.presentation.profiles.ProfilePickerStore
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.suggest.MockPicks
import com.edde746.tortuga.trackers.checkDevTrackerServer
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodedPath
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * A development session's screen to open (`--dev-open`, [DevLaunch]), as given on the command
 * line: [parse] reads `detail:<type>:<id>`, `streams:<type>:<id>:<videoId>`, a top-level destination's name, `addons`,
 * `watch`, `profile` or `restored`.
 */
sealed interface DevOpen {
    /** `streams:<type>:<id>:<videoId>`: the streams of [videoId] of the item [type]/[id]. */
    data class Streams(val type: String, val id: String, val videoId: String) : DevOpen

    /** `detail:<type>:<id>`: the Detail screen of the item [type]/[id]. */
    data class Detail(val type: String, val id: String) : DevOpen

    /** `board`, `discover`, `search`, `library` or `settings`: that top-level screen. */
    data class Destination(val destination: TopLevelDestination) : DevOpen

    /** `addons`: Settings on its Addons section, above the Board as the Settings tab opens it ([AppSession.openAddons]). */
    data object Addons : DevOpen

    /** `watch`: Watch together (the lobby) over Home, as Home's shortcut opens it ([AppSession.openWatchTogether]). */
    data object WatchTogether : DevOpen

    /** `profile`: the editor of a new profile over Home ([AppSession.editProfile] with no profile). */
    data object NewProfile : DevOpen

    /**
     * `restored`, alone: the back stacks the profile's last session left in a database kept from it (`--dev-data`,
     * or the desktop's `--data-dir`), as the product starts a profile's session on them
     * ([SavedNavigation]); the Board where it left none.
     */
    data object Restored : DevOpen

    companion object {
        /**
         * `detail:<type>:<id>`, `streams:<type>:<id>:<videoId>`, a destination's name, `addons`, `watch`, `profile` or
         * `restored`, where ids may contain colons: a Detail id is everything after the type, and a Streams id is the
         * shortest one its video id repeats (a movie) or starts with (an episode: `streams:series:tt0944947:tt0944947:1:1`
         * is id `tt0944947`, video `tt0944947:1:1`).
         *
         * @throws IllegalArgumentException for anything else, with the message to show.
         */
        @Throws(IllegalArgumentException::class)
        fun parse(spec: String): DevOpen {
            if (spec == WATCH) return WatchTogether
            if (spec == PROFILE) return NewProfile
            if (spec == RESTORED) return Restored
            TopLevelDestination.entries.firstOrNull { it.name.lowercase() == spec }?.let { return Destination(it) }
            if (spec == ADDONS) return Addons
            val parts = spec.split(':')
            when {
                parts.size >= 3 && parts[0] == "detail" && parts.drop(1).none { it.isEmpty() } ->
                    return Detail(parts[1], parts.drop(2).joinToString(":"))
                parts.size < 4 || parts[0] != "streams" || parts.drop(1).any { it.isEmpty() } ->
                    throw IllegalArgumentException("needs detail:<type>:<id>, streams:<type>:<id>:<videoId> or a destination ($DESTINATIONS), got '$spec'")
            }
            val type = parts[1]
            val rest = parts.drop(2)
            for (split in 1 until rest.size) {
                val id = rest.take(split).joinToString(":")
                val videoId = rest.drop(split).joinToString(":")
                if (videoId == id || videoId.startsWith("$id:")) return Streams(type, id, videoId)
            }
            return Streams(type, rest.first(), rest.drop(1).joinToString(":"))
        }

        private const val ADDONS = "addons"
        private const val WATCH = "watch"
        private const val PROFILE = "profile"
        private const val RESTORED = "restored"

        private val DESTINATIONS = (TopLevelDestination.entries.map { it.name.lowercase() } + ADDONS + PROFILE + RESTORED).joinToString("|")
    }
}

/**
 * Watch progress a development session starts with (`--dev-progress`, [DevLaunch]), as given on the command line:
 * [parse] reads `<type>:<id>:<videoId>:<seconds>:<duration seconds>` (`series:tt0944947:tt0944947:1:2:600:3300`,
 * `movie:tt0111161:tt0111161:2400:8520`), the item and its video split as a `streams:` [DevOpen]'s. [startDevSession]
 * records each as if just played there, so Continue Watching lists them in the order given; the marketing captures
 * fill it this way. Seconds equal to the duration play the video to its end ([watched]): it counts as watched, and a
 * series moves on to its next episode out, or leaves Continue Watching with none.
 */
data class DevProgress(val type: String, val id: String, val videoId: String, val seconds: Double, val durationSeconds: Double) {
    /** Played to its end. */
    val watched: Boolean get() = seconds == durationSeconds

    companion object {
        /** @throws IllegalArgumentException for anything else, with the message to show. */
        @Throws(IllegalArgumentException::class)
        fun parse(spec: String): DevProgress {
            val parts = spec.split(':')
            val seconds = parts.getOrNull(parts.size - 2)?.toDoubleOrNull()
            val duration = parts.last().toDoubleOrNull()
            val video = if (parts.size >= 5) {
                runCatching { DevOpen.parse("streams:" + parts.dropLast(2).joinToString(":")) }.getOrNull() as? DevOpen.Streams
            } else {
                null
            }
            require(video != null && seconds != null && duration != null && seconds > 0 && duration >= seconds) {
                "needs <type>:<id>:<videoId>:<seconds>:<duration seconds>, seconds within the duration, got '$spec'"
            }
            return DevProgress(video.type, video.id, video.videoId, seconds, duration)
        }
    }
}

/**
 * A video a development session starts with marked watched (`--dev-watched`, [DevLaunch]), as given on the command
 * line: [parse] reads `<type>:<id>:<videoId>` (`series:tt0944947:tt0944947:1:1`), split as a `streams:` [DevOpen]'s.
 * [startDevSession] marks each after recording the progress, so an episode list shows watched, started and unwatched
 * episodes side by side. A video id that is the title's own (`movie:tt0111161:tt0111161`) marks the title watched, as
 * Detail's Mark as watched does.
 */
data class DevWatched(val type: String, val id: String, val videoId: String) {
    companion object {
        /** @throws IllegalArgumentException for anything else, with the message to show. */
        @Throws(IllegalArgumentException::class)
        fun parse(spec: String): DevWatched {
            val video = runCatching { DevOpen.parse("streams:$spec") }.getOrNull() as? DevOpen.Streams
            requireNotNull(video) { "needs <type>:<id>:<videoId>, got '$spec'" }
            return DevWatched(video.type, video.id, video.videoId)
        }
    }
}

/**
 * The name, avatar and PIN of a development session's profile (`--dev-profile`, [DevLaunch]) instead of "Developer"
 * with no avatar chosen and no PIN, as given on the command
 * line: [parse] reads `<name>`, `<name>:<avatar>` or `<name>:<avatar>:<pin>`, the avatar by its [ProfileAvatars] id
 * (`Robin:sea`) or left out (`Robin::2468`), the PIN four digits. The store screenshots show an invented first name
 * where the app shows the profile; a PIN lets a new profile be a kids profile.
 */
data class DevProfile(val name: String, val avatar: String?, val pin: String?) {
    companion object {
        /** @throws IllegalArgumentException for a blank name, an unknown avatar or a PIN that isn't four digits, with the message to show. */
        @Throws(IllegalArgumentException::class)
        fun parse(spec: String): DevProfile {
            val parts = spec.split(':')
            val name = parts[0].trim()
            val avatar = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            val pin = parts.getOrNull(2)
            val avatars = ProfileAvatars.all.map { it.id }
            require(name.isNotEmpty() && parts.size <= 3) { "needs <name>, <name>:<avatar> or <name>:<avatar>:<pin>, got '$spec'" }
            require(avatar == null || avatar in avatars) { "the avatar is one of ${avatars.joinToString()}, got '$avatar'" }
            require(pin == null || (pin.length == ProfilePickerStore.PIN_LENGTH && pin.all(Char::isDigit))) {
                "the PIN is ${ProfilePickerStore.PIN_LENGTH} digits, got '$pin'"
            }
            return DevProfile(name, avatar, pin)
        }
    }
}

/**
 * A development session's profile as a kids profile (`--dev-kids`, [DevLaunch]),
 * with a parent beside it: [parse] reads the four-digit PIN [startDevSession] locks a profile named "Parent" with (adding
 * it, or keeping a kept session's), the PIN a parent check takes.
 */
data class DevKids(val parentPin: String) {
    companion object {
        /** @throws IllegalArgumentException for a PIN that isn't four digits, with the message to show. */
        @Throws(IllegalArgumentException::class)
        fun parse(spec: String): DevKids {
            require(spec.length == ProfilePickerStore.PIN_LENGTH && spec.all(Char::isDigit)) {
                "needs the parent's ${ProfilePickerStore.PIN_LENGTH}-digit PIN, got '$spec'"
            }
            return DevKids(spec)
        }
    }
}

/**
 * A title a development session starts with in its library (`--dev-library`, [DevLaunch]), as given on the command
 * line: [parse] reads `<type>:<id>` (`movie:tt0111161`,
 * `series:kitsu:1`). [startDevSession] adds each with its meta from the installed addons; `--dev-progress` items alone
 * are only in Continue Watching.
 */
data class DevLibraryItem(val type: String, val id: String) {
    companion object {
        /** @throws IllegalArgumentException for anything else, with the message to show. */
        @Throws(IllegalArgumentException::class)
        fun parse(spec: String): DevLibraryItem {
            val type = spec.substringBefore(':', "")
            val id = spec.substringAfter(':', "")
            require(type.isNotEmpty() && id.isNotEmpty()) { "needs <type>:<id>, got '$spec'" }
            return DevLibraryItem(type, id)
        }
    }
}

/** A [DevOpen] screen, ready to open. */
sealed interface DevScreen {
    val route: Route

    /** The Streams screen of [videoId] of [meta] (the meta from the installed addons, as Detail would pass it). */
    class Streams(val meta: MetaItem, val videoId: String) : DevScreen {
        override val route: Route get() = Route.Streams(meta.type, meta.id, videoId)
    }

    /** A screen whose store asks the addons itself: Detail, or a top-level screen. */
    class Screen(override val route: Route) : DevScreen

    /** Settings on its Addons section, above the Board ([AppSession.openAddons]). */
    data object Addons : DevScreen {
        override val route: Route get() = Route.Settings
    }

    /** Watch together (the lobby) over the Board ([AppSession.openWatchTogether]). */
    data object WatchTogether : DevScreen {
        override val route: Route get() = Route.Board
    }

    /** The editor of a new profile over the Board ([AppSession.editProfile] with no profile). */
    data object NewProfile : DevScreen {
        override val route: Route get() = Route.Board
    }
}

/**
 * Where a development session starts: its local [profile] as read once the start has set it up (so what opens at once
 * is checked against it, a kids profile's rules among it, as the account shell's sessions are against the gate's
 * read), and the [screens] to open, else the stacks [saved] restores ([DevOpen.Restored]), else the app's home screen;
 * or with [directPlay] (`--url`) the player alone. [devAppSession] opens them, with [signIn] as the session's sign-in
 * platform (a `--dev-relay` session's).
 */
class DevStart internal constructor(
    internal val profile: Profile,
    val screens: List<DevScreen>,
    internal val signIn: SignInPlatform?,
    internal val directPlay: String?,
    internal val saved: SavedStacks? = null,
) {
    /** The first screen, the app's start route; null for [saved]'s stacks or the home screen. */
    internal val start: Route? get() = screens.firstOrNull()?.route

    /** Opens [screens] in [app] (started on [start]), each on top of the one before, so Back returns to it. */
    internal fun open(app: AppSession) {
        val navigator = app.navigator
        for (screen in screens) {
            when (screen) {
                is DevScreen.Streams -> navigator.openStreams(screen.meta, screen.videoId)
                is DevScreen.Screen -> navigator.navigate(screen.route)
                DevScreen.Addons -> app.openAddons()
                DevScreen.WatchTogether -> app.openWatchTogether()
                DevScreen.NewProfile -> app.editProfile(null)
            }
        }
    }
}

/**
 * The app of a development session started by [startDevSession], without an account: [start]'s screens open, each on
 * top of the one before; or with its [DevStart.directPlay], the player alone on that URL ([directPlayRequest]), where
 * leaving the player leaves the app.
 */
fun AppGraph.devAppSession(start: DevStart, platform: AppPlatform): AppSession {
    val directPlay = start.directPlay
    if (directPlay != null) {
        val app = AppSession(this, start.profile, platform, Route.Player, signIn = start.signIn, saved = null)
        // The URL is played to test Tortuga's player, whatever Settings → Player says.
        app.playback.play(directPlayRequest(directPlay), PlayerApp.TORTUGA_ID)
        return app
    }
    val app = AppSession(this, start.profile, platform, start.start, signIn = start.signIn, saved = start.saved)
    start.open(app)
    return app
}

/** A development session can't start; [message] says why, for the user. */
class DevStartException(message: String) : Exception(message)

/** The dev profile's name without `--dev-profile`. */
internal const val DEV_PROFILE_NAME = "Developer"

/** The profile [DevKids] adds beside a kids dev profile, locked with the parent's PIN. */
internal const val DEV_PARENT_NAME = "Parent"

/**
 * Starts the development session [launch] asks for on this graph (built by [AppBootstrap.dev] with its
 * [DevLaunch.trackers]): a local profile with the default addons (the one already there when the database is kept
 * from an earlier session, `--dev-data`), named, given its avatar and locked with its PIN by its [DevLaunch.profile],
 * made a kids profile with a parent profile beside it by its [DevLaunch.kids], its interface language set to its
 * [DevLaunch.language] (which the session's [AppSession] shows the app in, as the profile's setting does in the
 * product: the store screenshots in each of the app's languages), its [DevLaunch.picks] seeded, its stream
 * suggestions switched by its [DevLaunch.suggestions] and the blur of unwatched episodes by its
 * [DevLaunch.blurUnwatched], then its [DevLaunch.addons] installed into it where
 * it doesn't have them yet, with its [DevLaunch.watchActivity] the agreement the install dialog asks the user for
 * ([com.edde746.tortuga.addons.AddonRegistry.install]), then, with [DevLaunch.addonsOnly], every other addon removed
 * from it (no Cinemeta and no OpenSubtitles, whose real catalogs and subtitles the store screenshots must not show),
 * then, with its trackers, the fake tracker server checked to agree with this session ([checkDevTrackerServer]), then,
 * with [DevTrackers.connected], every tracker service connected to it, then the fake's tracker connections listed,
 * then the [DevLaunch.library] titles added, then the [DevLaunch.progress] recorded and the [DevLaunch.watched] videos
 * marked (each item's meta from the installed addons), then the [DevLaunch.open] screens resolved (a Streams screen's
 * item from the installed addons), or for [DevOpen.Restored] the stacks the profile's last session left read.
 * [applyDevSettings] stores its settings.
 *
 * @param signIn this device's sign-in platform, which a `--dev-relay` session's tracker sign-ins open its browser
 *   with; the other sessions have none.
 * @throws DevStartException when an addon doesn't install, the fake tracker server disagrees with the session or
 *   doesn't answer, or no addon has a Streams screen's, a progress's, a watched video's or a library title's item.
 */
@Throws(DevStartException::class, CancellationException::class)
suspend fun AppGraph.startDevSession(launch: DevLaunch, signIn: SignInPlatform?): DevStart {
    val profile = launch.profile
    val trackers = launch.trackers
    val open = launch.open
    val sessionSignIn = signIn.takeIf { trackers?.relay != null }
    val name = profile?.name ?: DEV_PROFILE_NAME
    val kept = data.profiles.profiles().firstOrNull { it.name == DEV_PROFILE_NAME || it.name == name }
    if (kept != null && profile != null) {
        if (kept.name != name) data.profiles.rename(kept.id, name)
        if (profile.avatar != null && kept.avatar != profile.avatar) data.profiles.setAvatar(kept.id, profile.avatar)
    }
    val profileId = kept?.id ?: createProfile(name, profile?.avatar).id
    profile?.pin?.let { pin -> if (!data.profiles.verifyPin(profileId, pin)) data.profiles.setPin(profileId, pin) }
    launch.language?.let { data.settings.set(profileId, ProfileSetting.InterfaceLanguage, it) }
    launch.kids?.let { kids ->
        val parent = data.profiles.profiles().firstOrNull { it.name == DEV_PARENT_NAME } ?: createProfile(DEV_PARENT_NAME, null)
        if (!data.profiles.verifyPin(parent.id, kids.parentPin)) data.profiles.setPin(parent.id, kids.parentPin)
        data.profiles.setKids(profileId, true, emptyList())
    }
    // A database kept from an earlier session (`--dev-data`) has its picks already.
    launch.picks?.takeIf { data.streamPicks.picks(profileId).isEmpty() }?.let { user ->
        data.settings.set(profileId, ProfileSetting.AudioLanguages, user.audioLanguages)
        data.settings.set(profileId, ProfileSetting.SubtitleLanguages, user.subtitleLanguages)
        for (pick in MockPicks.seed(user)) {
            data.streamPicks.record(profileId, pick)
            pick.outcome?.let { data.streamPicks.setOutcome(pick.id, it) }
        }
    }
    launch.suggestions?.let { data.settings.set(profileId, ProfileSetting.StreamSuggestions, it) }
    if (launch.blurUnwatched) data.settings.set(profileId, ProfileSetting.BlurUnwatched, true)
    val registry = addonRegistry(profileId)
    for (url in launch.addons) {
        try {
            registry.install(TransportUrl.parse(url) ?: throw DevStartException("the addon $url isn't at a transport URL"), launch.watchActivity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AddonException) {
            // A database kept from an earlier session (`--dev-data`) has it already.
            if (e.error != AddonError.AlreadyInstalled) throw DevStartException("installing the addon $url failed: ${e.message ?: e}")
        } catch (e: Exception) {
            throw DevStartException("installing the addon $url failed: ${e.message ?: e}")
        }
    }
    if (launch.addonsOnly) {
        // The registry refuses to remove the protected Cinemeta, so the stored list is replaced as a whole.
        val stored = data.addons.addons(profileId).associateBy { it.transportUrl }
        val given = launch.addons.mapNotNull { TransportUrl.parse(it)?.value }.distinct().mapNotNull { stored[it] }
        data.addons.replaceAll(profileId, given.map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) })
        registry.load()
    }
    if (trackers != null) {
        try {
            checkDevTrackerServer(http, trackers.baseUrl, DEV_ACCOUNT_PREFIX, DEV_TRACKER_USER, DEV_TRACKER_TOKEN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DevStartException(e.message ?: e.toString())
        }
    }
    if (trackers?.connected == true) connectDevTrackers(profileId)
    // The fake account server's connections, as the product lists the account's at start.
    this.trackers?.accounts?.syncConnections()
    val saved = if (DevOpen.Restored in open) data.deviceSettings.value(SavedNavigation(profileId)) else null
    val stored = checkNotNull(data.profiles.profile(profileId)) { "the development profile $profileId isn't stored" }
    if (open.isEmpty() && launch.progress.isEmpty() && launch.watched.isEmpty() && launch.library.isEmpty()) {
        return DevStart(stored, emptyList(), sessionSignIn, launch.url)
    }
    val installed = registry.load()
    suspend fun meta(type: String, id: String, what: String): MetaItem {
        val answers = aggregator.meta(installed, type, id).last()
        return answers.firstNotNullOfOrNull { (it.state as? Loadable.Ready)?.content }
            ?: throw DevStartException("no installed addon has $type/$id for $what (${answers.size} asked)")
    }
    // The library and Continue Watching list the most recently changed first: each recorded from the last at one
    // millisecond after the one before, ending now; the progress after the library, so its order stays as given.
    var at = Clock.System.now().toEpochMilliseconds() - launch.library.size - launch.progress.size
    for (item in launch.library.asReversed()) {
        data.library.addToLibrary(profileId, meta(item.type, item.id, "--dev-library").librarySnapshot(), at = ++at)
    }
    for (entry in launch.progress.asReversed()) {
        val meta = meta(entry.type, entry.id, "--dev-progress")
        val written = ++at
        data.library.upsertFromMeta(profileId, meta.librarySnapshot(), at = written)
        data.library.recordProgress(
            profileId, meta.id, entry.videoId, (entry.seconds * 1000).toLong(), (entry.durationSeconds * 1000).toLong(), meta,
            watched = entry.watched, at = written,
        )
    }
    for (entry in launch.watched) {
        val meta = meta(entry.type, entry.id, "--dev-watched")
        if (entry.videoId == entry.id) data.library.markAllWatched(profileId, meta, true) else data.library.markVideoWatched(profileId, meta, entry.videoId, true)
    }
    val screens = open.mapNotNull { screen ->
        when (screen) {
            is DevOpen.Detail -> DevScreen.Screen(Route.Detail(screen.type, screen.id))
            is DevOpen.Destination -> DevScreen.Screen(screen.destination.route)
            DevOpen.Addons -> DevScreen.Addons
            DevOpen.WatchTogether -> DevScreen.WatchTogether
            DevOpen.NewProfile -> DevScreen.NewProfile
            is DevOpen.Streams -> DevScreen.Streams(meta(screen.type, screen.id, "--dev-open"), screen.videoId)
            DevOpen.Restored -> null
        }
    }
    return DevStart(stored, screens, sessionSignIn, launch.url, saved)
}

/**
 * A development session's direct play: a movie of its own whose only stream is [url]. The id is a hash, so the URL
 * (which may carry a key) isn't repeated in ids, the library or logs; the title is the file name.
 */
internal fun directPlayRequest(url: String): PlayRequest {
    val digest = CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(url.encodeToByteArray()).take(8)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    val id = "tortuga-dev:$digest"
    val fileName = runCatching { Url(url).encodedPath.substringAfterLast('/').decodeURLPart() }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: id
    val meta = MetaItem(MetaItemPreview(id = id, type = "movie", name = fileName))
    return PlayRequest(meta, videoId = id, stream = StreamChoice(Stream(StreamSource.Url(url), name = fileName), addonUrl = "", addonName = ""))
}
