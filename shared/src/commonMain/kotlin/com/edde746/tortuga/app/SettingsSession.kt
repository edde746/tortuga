package com.edde746.tortuga.app

import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.playback.players.PlayerAppCatalog
import com.edde746.tortuga.presentation.addons.AddonsStore
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.settings.AccountSettingsHost
import com.edde746.tortuga.presentation.settings.AccountSettingsStore
import com.edde746.tortuga.presentation.settings.AppearanceSettingsHost
import com.edde746.tortuga.presentation.settings.AppearanceSettingsStore
import com.edde746.tortuga.presentation.settings.DataSettingsStore
import com.edde746.tortuga.presentation.settings.DeviceSettingsStore
import com.edde746.tortuga.presentation.settings.DownloadSettingsStore
import com.edde746.tortuga.presentation.settings.HomeSettingsStore
import com.edde746.tortuga.presentation.settings.PlaybackSettingsStore
import com.edde746.tortuga.presentation.settings.PlayerSettingsStore
import com.edde746.tortuga.presentation.settings.SettingsHost
import com.edde746.tortuga.presentation.settings.SettingsSection
import com.edde746.tortuga.presentation.settings.SettingsStore
import com.edde746.tortuga.presentation.settings.SubtitleSettingsStore
import com.edde746.tortuga.presentation.settings.TrackersSettingsHost
import com.edde746.tortuga.presentation.settings.TrackersSettingsStore
import com.edde746.tortuga.presentation.settings.accountSettingsStore
import com.edde746.tortuga.presentation.settings.appearanceSettingsStore
import com.edde746.tortuga.presentation.settings.dataSettingsStore
import com.edde746.tortuga.presentation.settings.deviceSettingsStore
import com.edde746.tortuga.presentation.settings.downloadSettingsStore
import com.edde746.tortuga.presentation.settings.homeSettingsStore
import com.edde746.tortuga.presentation.settings.playbackSettingsStore
import com.edde746.tortuga.presentation.settings.playerSettingsStore
import com.edde746.tortuga.presentation.settings.settingsStore
import com.edde746.tortuga.presentation.settings.subtitleSettingsStore
import com.edde746.tortuga.presentation.settings.trackersSettingsStore
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.sync.account.DeviceLinkQr

/**
 * The stores of Settings ([AppSession.settingsSession]): the first page's ([main], which also holds the section on
 * screen) and each section's, created when the section is first shown and released with the session. Settings is a
 * destination: its route stays at the bottom of its stack, and the navigator keeps its stores until the app session
 * closes ([TortugaNavigator.retain][com.edde746.tortuga.presentation.navigation.TortugaNavigator.retain]), so a section left open
 * (and its store: Addons' install or details sheet over a trip to the browser) is still there when the user comes back
 * from a screen it opened, from the browser or from another tab. The sections' ports lead to [app] and the
 * [platform]: the browser, the clipboard, the system language, the device's capabilities (detected as the session
 * starts).
 *
 * Main thread only.
 */
class SettingsSession internal constructor(private val app: AppSession, private val platform: AppPlatform) {
    private val hosts = Hosts()
    /** What the platform detects, and Discord's switch where the platform speaks to Discord. */
    private val deviceFeatures = platform.deviceFeatures().copy(discordPresence = platform.discordPresence != null)
    private var released = false

    /** The first page and the section on screen. */
    val main: SettingsStore =
        app.graph.settingsStore(app.profileId, app.currentProfile, app.sync, app.graph.appVersion, hosts, platform.hasFileDialogs)

    /** The playback section's store once shown; [playback] creates it. */
    var playbackStore: PlaybackSettingsStore? = null
        private set

    /** The appearance section's store once shown; [appearance] creates it. */
    var appearanceStore: AppearanceSettingsStore? = null
        private set

    /** The device section's store once shown; [device] creates it. */
    var deviceStore: DeviceSettingsStore? = null
        private set

    /** The subtitles section's store once shown; [subtitles] creates it. */
    var subtitlesStore: SubtitleSettingsStore? = null
        private set

    /** The account section's store once shown; [account] creates it. */
    var accountStore: AccountSettingsStore? = null
        private set

    /** The trackers section's store once shown; [trackers] creates it. */
    var trackersStore: TrackersSettingsStore? = null
        private set

    /** The home section's store once shown; [home] creates it. */
    var homeStore: HomeSettingsStore? = null
        private set

    /** The downloads section's store once shown; [downloads] creates it. */
    var downloadsStore: DownloadSettingsStore? = null
        private set

    /** The export and import section's store once shown; [data] creates it. */
    var dataStore: DataSettingsStore? = null
        private set

    /** The Addons section's store once shown; [addons] creates it. */
    var addonsStore: AddonsStore? = null
        private set

    /** The Player page's store once shown; [player] creates it. */
    var playerStore: PlayerSettingsStore? = null
        private set

    /** The playback section's store, created on first use; null once the session is released. */
    val playback: PlaybackSettingsStore?
        get() = playbackStore ?: if (released) {
            null
        } else {
            app.graph.playbackSettingsStore(app.profileId, platform.playerConfig.profile, playerCatalog != null)
                .also { playbackStore = it }
        }

    /** The appearance section's store, created on first use; null once released. */
    val appearance: AppearanceSettingsStore?
        get() = appearanceStore ?: if (released) {
            null
        } else {
            app.graph.appearanceSettingsStore(app.profileId, app.kidsActive, hosts).also { appearanceStore = it }
        }

    /** The device section's store, created on first use with what this device supports; null once released. */
    val device: DeviceSettingsStore?
        get() = deviceStore ?: if (released) null else app.graph.deviceSettingsStore(deviceFeatures, platform.formFactor).also { deviceStore = it }

    /** The subtitles section's store, created on first use; null once released. */
    val subtitles: SubtitleSettingsStore?
        get() = subtitlesStore ?: if (released) null else app.graph.subtitleSettingsStore().also { subtitlesStore = it }

    /**
     * The account section's store, created on first use; null without an account (a development session), on a kids
     * profile, or once released.
     */
    val account: AccountSettingsStore?
        get() {
            accountStore?.let { return it }
            if (released || app.kidsActive) return null
            val account = app.account ?: return null
            val sync = app.sync ?: return null
            val signIn = app.signIn ?: return null
            return app.graph.accountSettingsStore(account, sync, signIn, hosts).also { accountStore = it }
        }

    /** The trackers section's store, created on first use; null without trackers on the graph, on a kids profile, or once released. */
    val trackers: TrackersSettingsStore?
        get() = trackersStore ?: if (released || app.kidsActive) null else app.graph.trackersSettingsStore(app.profileId, hosts, app.signIn, tv = platform.formFactor == FormFactor.TV)?.also { trackersStore = it }

    /** The home section's store, created on first use; null on a kids profile or once released. */
    val home: HomeSettingsStore?
        get() = homeStore ?: if (released || app.kidsActive) null else app.graph.homeSettingsStore(app.profileId, platform.hasFileDialogs).also { homeStore = it }

    /**
     * The downloads section's store, created on first use; null where this device has no downloads, on a kids profile,
     * or once released.
     */
    val downloads: DownloadSettingsStore?
        get() = downloadsStore ?: if (released || app.kidsActive) null else app.graph.downloadSettingsStore(app.profileId)?.also { downloadsStore = it }

    /** The export and import section's store, created on first use; null on a kids profile or once released. */
    val data: DataSettingsStore?
        get() = dataStore ?: if (released || app.kidsActive) null else app.graph.dataSettingsStore().also { dataStore = it }

    /** The Addons section's store, created on first use; null on a kids profile or once released. */
    val addons: AddonsStore?
        get() = addonsStore ?: if (released || app.kidsActive) null else app.addonsStore().also { addonsStore = it }

    /**
     * Playback's Player row and page (this device's player), created on first use; null where [playerCatalog] is, or
     * once released.
     */
    val player: PlayerSettingsStore?
        get() {
            playerStore?.let { return it }
            if (released) return null
            val catalog = playerCatalog ?: return null
            return app.graph.playerSettingsStore(catalog).also { playerStore = it }
        }

    /**
     * The player apps Playback's Player chooses among; null where the platform has none (`AppPlatform.playerApps`) and
     * on a kids profile, which plays in Tortuga's player only.
     */
    private val playerCatalog: PlayerAppCatalog?
        get() = if (app.kidsActive) null else app.playerApps

    /**
     * Shows the Addons section; with [installLink] (the transport URL of a followed `stremio://…/manifest.json` link),
     * the install preview of that addon over it.
     */
    internal fun openAddons(installLink: TransportUrl?) {
        main.open(SettingsSection.ADDONS)
        if (installLink != null) addons?.previewInstall(installLink)
    }

    /**
     * Shows the Account section checking [qr], a device link's QR code (`tortuga://link?…`) for this account to
     * approve; without a signed-in account the section alone, the code dropped.
     */
    internal fun openDeviceLink(qr: DeviceLinkQr) {
        main.open(SettingsSection.ACCOUNT)
        account?.submitScannedLink(qr)
    }

    /** Releases every store; the navigator calls it when the app session closes. */
    internal fun release() {
        released = true
        main.release()
        playbackStore?.release()
        appearanceStore?.release()
        deviceStore?.release()
        subtitlesStore?.release()
        accountStore?.release()
        trackersStore?.release()
        homeStore?.release()
        downloadsStore?.release()
        dataStore?.release()
        addonsStore?.release()
        playerStore?.release()
    }

    /** Where the settings stores lead: the app's screens and overlays, and the OS through [platform]. */
    private inner class Hosts : SettingsHost, AppearanceSettingsHost, AccountSettingsHost, TrackersSettingsHost {
        override fun editProfile(profileId: String) = app.editProfile(profileId)

        override fun manageProfiles() = app.manageProfiles()

        override fun openUrl(url: String) = app.openUrl(url)

        override fun bundledTexts(folder: String) = platform.bundledTexts(folder)

        // Null is the system's language, as the platform reports it (InterfaceLocale).
        override fun interfaceLanguageChanged(tag: String?) {
            InterfaceLocale.setChosen(tag)
        }

        override fun linkIdentity() = app.linkIdentity()

        override fun accountDeleted(message: String) = app.accountDeleted(message)
    }

    companion object {
        /** Its key on the navigator, under [Route.Settings] (test reports read it there). */
        const val KEY: String = "settings"
    }
}
