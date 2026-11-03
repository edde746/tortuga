package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.players.PlayerAppCatalog
import com.edde746.tortuga.presentation.account.providerFlows
import com.edde746.tortuga.presentation.board.installedAddonsFlow
import com.edde746.tortuga.presentation.board.trackerCatalogSources
import com.edde746.tortuga.presentation.calendar.localUtcOffsetSeconds
import com.edde746.tortuga.presentation.collections.collectionEditorStore
import com.edde746.tortuga.presentation.collections.collectionImportStore
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.engine.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.map

/**
 * The settings screen's first page for [profileId], the current profile; a kids profile's when the profile is one.
 *
 * @param profile the profile as the running session has it (null while it isn't read yet).
 * @param engine the account's sync engine; null without an account (a development session), which leaves out the
 *   Account section.
 * @param appVersion as shown in About ("1.0.0").
 * @param offersData the platform has the system's file dialogs (`AppPlatform.hasFileDialogs`): Export and import is
 *   offered.
 */
fun AppGraph.settingsStore(
    profileId: String,
    profile: Profile?,
    engine: SyncEngine?,
    appVersion: String,
    host: SettingsHost,
    offersData: Boolean,
): SettingsStore =
    SettingsStore(
        profileId = profileId,
        profiles = data.profiles,
        registry = addonRegistry(profileId),
        engine = engine,
        trackers = trackers?.accounts,
        appVersion = appVersion,
        platform = device.platform,
        host = host,
        parentScope = scope,
        offersDownloads = downloads != null,
        offersData = offersData,
        startProfile = profile,
        io = Dispatchers.IO,
    )

/**
 * The playback settings of [profileId] (per profile, synced); [inputProfile] is the platform's, which
 * decides whether the video's click row shows, and [playerChoice] says the pane heads with this device's Player
 * ([playerSettingsStore]).
 */
fun AppGraph.playbackSettingsStore(
    profileId: String,
    inputProfile: InputProfile,
    playerChoice: Boolean,
): PlaybackSettingsStore = PlaybackSettingsStore(profileId, data.settings, inputProfile, playerChoice, scope)

/** This device's player (Settings → Playback → Player) among [catalog]'s player apps. */
internal fun AppGraph.playerSettingsStore(catalog: PlayerAppCatalog): PlayerSettingsStore =
    PlayerSettingsStore(catalog, data.deviceSettings, scope)

/** This device's settings; [features] says which apply here, [formFactor] what passthrough defaults to. */
fun AppGraph.deviceSettingsStore(features: DeviceFeatures, formFactor: FormFactor): DeviceSettingsStore =
    DeviceSettingsStore(data.deviceSettings, features, formFactor, scope)

/** This device's subtitle style. */
fun AppGraph.subtitleSettingsStore(): SubtitleSettingsStore = SubtitleSettingsStore(data.deviceSettings, scope)

/**
 * This device's download settings, with [profileId]'s subtitle languages as the default of the ones saved; null where
 * this device has no downloads (Apple TV).
 */
fun AppGraph.downloadSettingsStore(profileId: String): DownloadSettingsStore? {
    val manager = downloads ?: return null
    return DownloadSettingsStore(manager, data.deviceSettings, data.settings.observe(profileId), scope)
}

/**
 * The account settings over the process's [account] and its sync [engine]; [signIn] is what this device can do to
 * sign in, which decides the sign-in methods it can add.
 */
fun AppGraph.accountSettingsStore(account: SyncAccount, engine: SyncEngine, signIn: SignInPlatform, host: AccountSettingsHost): AccountSettingsStore =
    AccountSettingsStore(account, engine, providerFlows(account, signIn), host, account.clock, scope)

/**
 * The tracker accounts of [profileId] on this device; null when this graph has no trackers installed. [signIn]: what
 * this device can do to sign in (its own browser for a relay link); null in a development session without a relay.
 * [tv]: the device is a TV, whose user signs in on a phone.
 */
fun AppGraph.trackersSettingsStore(profileId: String, host: TrackersSettingsHost, signIn: SignInPlatform?, tv: Boolean): TrackersSettingsStore? {
    val hub = trackers ?: return null
    return TrackersSettingsStore(profileId, hub.accounts, data.trackerWriteQueue, host, signIn, tv, { clock.now().toEpochMilliseconds() }, scope)
}

/**
 * Settings → Appearance of [profileId] (per profile, synced); a [kids] profile's has no poster URL template. [host]
 * takes a change of the interface language.
 */
fun AppGraph.appearanceSettingsStore(profileId: String, kids: Boolean, host: AppearanceSettingsHost): AppearanceSettingsStore =
    AppearanceSettingsStore(profileId, kids, data.settings, host, scope)

/**
 * Home's rows of [profileId] (per profile, synced): its collections, the installed addons' catalogs and this device's
 * trackers' lists, with the collections' import ([offersFiles]: the platform has file dialogs) and editor.
 */
fun AppGraph.homeSettingsStore(profileId: String, offersFiles: Boolean): HomeSettingsStore = HomeSettingsStore(
    profileId,
    installedAddonsFlow(profileId),
    trackerCatalogSources(profileId),
    data.trackerAccounts.observeAccounts(profileId).map { accounts -> accounts.mapTo(HashSet()) { it.service } },
    data.settings,
    data.collections,
    scope,
    newImport = { collectionImportStore(profileId, offersFiles) },
    newEditor = { collectionId, close -> collectionEditorStore(profileId, collectionId, close) },
    now = { clock.now().toEpochMilliseconds() },
    utcOffset = ::localUtcOffsetSeconds,
    disabledAddons = { addonRegistry(profileId).installed.value.filterNot { it.enabled }.mapTo(HashSet()) { it.descriptor.transportUrl } },
)

/**
 * Export and import of the account's profiles as a file; a profile the import creates gets the default addons unless
 * the file gave it some, as a profile created here does ([AppGraph.createProfile]).
 */
fun AppGraph.dataSettingsStore(): DataSettingsStore = DataSettingsStore(
    profiles = data.profiles,
    transfer = data.transfer,
    appVersion = appVersion,
    now = { clock.now().toEpochMilliseconds() },
    utcOffset = ::localUtcOffsetSeconds,
    work = parseDispatcher,
    seedAddons = { addonRegistry(it).seedDefaults() },
    parentScope = scope,
)
