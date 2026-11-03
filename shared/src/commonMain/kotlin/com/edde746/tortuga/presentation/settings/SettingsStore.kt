package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.addons.AddonRegistry
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.profile.ProfileRepository
import com.edde746.tortuga.data.tracker.TrackerAccount
import com.edde746.tortuga.kids.isKidsProfile
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.presentation.profiles.ProfileAvatars
import com.edde746.tortuga.presentation.profiles.profileInitial
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.trackers.TrackerAccounts
import com.edde746.tortuga.trackers.TrackerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The settings screen's first page: the current profile, the sections with a one-line summary each (the
 * installed-addon count, the connected trackers and the sync status follow live), the section on screen, and About
 * (version, GPL-3.0 with the source link, and the third-party notices of what the app ships on [platform], one of
 * `DevicePlatform`, each with a page of its licence texts, which the app carries in its bundled resources and [io]
 * reads).
 *
 * Without an [engine] (a development session, which has no account) the Account section is left out; without
 * [trackers], or when this build can connect no tracker service, the Trackers section is; without downloads on this
 * device ([offersDownloads]: Apple TV), the Downloads section is; without the system's file dialogs ([offersData]:
 * Apple TV, Android TV), the Data section is.
 *
 * On a kids profile (and while the profile isn't read yet) only Playback, Device, Subtitles and About are offered,
 * and the profile card has no Edit profile: no addons, Home rows, trackers, account, downloads or data. The profile
 * starts as the session read it ([startProfile]), so a profile that isn't a kids profile has its sections at once.
 *
 * Call everything on the main thread; [release] ends it.
 */
class SettingsStore internal constructor(
    private val profileId: String,
    private val profiles: ProfileRepository,
    private val registry: AddonRegistry,
    private val engine: SyncEngine?,
    private val trackers: TrackerAccounts?,
    private val appVersion: String,
    platform: String,
    private val host: SettingsHost,
    parentScope: CoroutineScope,
    private val offersDownloads: Boolean,
    private val offersData: Boolean,
    /** The profile as the running session has it; null while it isn't read yet. */
    startProfile: Profile?,
    private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val notices = ThirdPartyNotices.forPlatform(platform)

    private var profile: Profile? = startProfile
    private var addonCount: Int? = null
    private var trackerAccounts: List<TrackerAccount> = emptyList()
    private var section: SettingsSection? = null

    /** The notice whose page shows in About's place ([AboutUiState.notice]), and its texts once read. */
    private var shownNotice: Int? = null
    private var noticeTexts: List<NoticeText>? = null
    private var noticeRead: Job? = null

    /** The build can connect a tracker service: the Trackers section is offered. */
    private val offersTrackers = trackers != null && trackers.services.isNotEmpty()

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            profiles.observeProfile(profileId).collect {
                profile = it
                publish()
            }
        }
        scope.launch {
            // The registry's list is empty until read: count only after that (or after the graph's next read, when this
            // one fails).
            try {
                registry.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("settings", e) { "reading the installed addons failed (${e::class.simpleName})" }
            }
            registry.addons.collect {
                addonCount = it.size
                publish()
            }
        }
        engine?.let { scope.launch { it.status.collect { publish() } } }
        if (offersTrackers) {
            scope.launch {
                checkNotNull(trackers).observe(profileId).collect {
                    trackerAccounts = it
                    publish()
                }
            }
        }
    }

    /**
     * Shows [section] ([SettingsUiState.section]) where the settings offer it. While the profile isn't read yet, the
     * sections a kids profile lacks aren't listed: the section waits for the read and shows once the profile turns out
     * to have it (a followed install link's Addons as Settings first opens).
     */
    fun open(section: SettingsSection) {
        val waiting = profile == null
        if (!waiting && section != SettingsSection.ABOUT && mutableState.value.sections.none { it.section == section }) return
        this.section = section
        dropNotice()
        publish()
    }

    /** Back to the list alone (narrow windows); wide windows then show the first section. */
    fun closeSection() {
        section = null
        dropNotice()
        publish()
    }

    /** Opens the profile's editor; not on a kids profile ([SettingsProfileCard.editLabel] is null there). */
    fun editProfile() {
        if (!kids()) host.editProfile(profileId)
    }

    fun manageProfiles() = host.manageProfiles()

    fun openSourceCode() = host.openUrl(ThirdPartyNotices.SOURCE_URL)

    fun openLicense() = host.openUrl(ThirdPartyNotices.LICENSE_URL)

    /** Opens the project page of [AboutUiState.notices]' entry at [index] in the browser. */
    fun openNotice(index: Int) {
        notices.getOrNull(index)?.let { host.openUrl(it.url) }
    }

    /**
     * Shows the page of [AboutUiState.notices]' entry at [index] in About's place ([AboutUiState.notice]), its licence
     * texts once [io] has read them.
     */
    fun showNotice(index: Int) {
        val notice = notices.getOrNull(index) ?: return
        dropNotice()
        shownNotice = index
        publish()
        noticeRead = scope.launch {
            noticeTexts = withContext(io) { readTexts(notice) }
            publish()
        }
    }

    /** Back from a notice's page to About's list. */
    fun closeNotice() {
        dropNotice()
        publish()
    }

    fun release() {
        scope.cancel()
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun dropNotice() {
        noticeRead?.cancel()
        noticeRead = null
        shownNotice = null
        noticeTexts = null
    }

    /**
     * [notice]'s licence files: every file under each of its folders, its text in paragraphs ([reflowLicense]). A
     * folder that yields none (one this build's resources lack: the build's checks keep that from happening) is logged
     * and its texts left out.
     */
    private fun readTexts(notice: ThirdPartyNotice): List<NoticeText> = notice.texts.flatMap { folder ->
        val files = try {
            host.bundledTexts(folder)
        } catch (e: Exception) {
            Log.e("settings", e) { "${notice.name}: reading the licence texts in $folder failed" }
            return@flatMap emptyList()
        }
        if (files.isEmpty()) Log.e("settings") { "${notice.name}: no licence text in $folder" }
        files.map { NoticeText(it.path, reflowLicense(it.text)) }
    }

    /** The settings are a kids profile's ([isKidsProfile]: also while the profile isn't read yet). */
    private fun kids(): Boolean = isKidsProfile(profile)

    private fun buildState(): SettingsUiState {
        val text = T.settings
        val sections = text.sections
        val kids = kids()
        val rows = listOfNotNull(
            SettingsSectionRow(SettingsSection.PLAYBACK, sections.playback, sections.playbackSummary),
            SettingsSectionRow(SettingsSection.APPEARANCE, sections.appearance, sections.appearanceSummary),
            SettingsSectionRow(SettingsSection.DEVICE, sections.device, sections.deviceSummary),
            SettingsSectionRow(SettingsSection.SUBTITLES, sections.subtitles, sections.subtitlesSummary),
            if (offersDownloads && !kids) SettingsSectionRow(SettingsSection.DOWNLOADS, sections.downloads, sections.downloadsSummary) else null,
            if (kids) {
                null
            } else {
                SettingsSectionRow(
                    SettingsSection.ADDONS,
                    sections.addons,
                    addonCount?.let { sections.addonsSummary(it) } ?: sections.addonsSummaryLoading,
                )
            },
            if (kids) null else SettingsSectionRow(SettingsSection.HOME, sections.home, sections.homeSummary),
            if (offersTrackers && !kids) SettingsSectionRow(SettingsSection.TRACKERS, sections.trackers, trackersSummary()) else null,
            engine?.takeUnless { kids }?.let { SettingsSectionRow(SettingsSection.ACCOUNT, sections.account, it.status.value.text) },
            if (offersData && !kids) SettingsSectionRow(SettingsSection.DATA, sections.data, sections.dataSummary) else null,
            SettingsSectionRow(SettingsSection.ABOUT, sections.about, text.about.version(appVersion)),
        )
        return SettingsUiState(
            title = text.title,
            profile = profile?.let {
                SettingsProfileCard(
                    name = it.name,
                    initial = profileInitial(it.name),
                    avatar = ProfileAvatars.forProfile(it.avatar, it.id),
                    detail = if (it.hasPin) text.profile.locked else text.profile.unlocked,
                    editLabel = text.profile.edit.takeUnless { kids },
                    manageLabel = text.profile.manage,
                )
            },
            sections = rows,
            // A section no longer offered (the profile turned out to be a kids profile) closes.
            section = section?.takeIf { shown -> rows.any { it.section == shown } },
            about = AboutUiState(
                appName = T.app.name,
                version = text.about.version(appVersion),
                license = text.about.license,
                sourceLabel = text.about.source,
                licenseLabel = text.about.licenseLink,
                traktCredit = text.about.traktCredit,
                noticesHeader = text.about.notices,
                notices = notices.map { AboutNoticeRow(it.name, it.license) },
                notice = shownNotice?.let { index ->
                    val notice = notices[index]
                    NoticePage(index, notice.name, notice.license, text.about.noticeLink, noticeTexts)
                },
            ),
        )
    }

    /** "Connected to Trakt, Simkl", or what the section is for while nothing is connected. */
    private fun trackersSummary(): String {
        val names = TrackerService.entries.filter { service -> trackerAccounts.any { it.service == service } }.map(::serviceName)
        return if (names.isEmpty()) T.settings.sections.trackersSummary else T.settings.sections.trackersConnected(names.joinToString(T.settings.listSeparator))
    }
}
