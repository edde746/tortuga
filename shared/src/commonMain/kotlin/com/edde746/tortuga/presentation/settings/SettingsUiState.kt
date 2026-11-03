package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.app.BundledText
import com.edde746.tortuga.presentation.profiles.ProfileAvatar

/**
 * The settings screen's first page: the current profile (edit it, or switch and manage profiles), one row per
 * section, and About.
 *
 * @param profile null while the profile is read, or once it was deleted elsewhere.
 * @param section the section shown (`open(section)`, `closeSection`), always one of [sections]; null shows the list
 *   alone where the list and a section don't fit side by side (wide windows show the first section then).
 */
data class SettingsUiState(
    val title: String,
    val profile: SettingsProfileCard?,
    val sections: List<SettingsSectionRow>,
    val section: SettingsSection?,
    val about: AboutUiState,
) {
    /** The heading of [section]'s page: its row's title; empty for a section no longer listed. */
    fun sectionTitle(section: SettingsSection): String = sections.firstOrNull { it.section == section }?.title.orEmpty()
}

/**
 * The current profile. `editProfile` opens its editor (name, avatar, PIN, delete); `manageProfiles` the profile
 * picker's manage mode (other profiles, which ask for their PIN first; on a kids profile a parent's PIN comes first).
 *
 * @param initial and [avatar] the profile's avatar, as the profile picker and editor show it.
 * @param detail "Locked with a PIN" or "No PIN".
 * @param editLabel "Edit profile"; null on a kids profile, which isn't edited from itself (no Edit profile).
 */
data class SettingsProfileCard(
    val name: String,
    val initial: String,
    val avatar: ProfileAvatar,
    val detail: String,
    val editLabel: String?,
    val manageLabel: String,
)

/** A section of settings; `open(section)` shows it. */
data class SettingsSectionRow(val section: SettingsSection, val title: String, val summary: String)

enum class SettingsSection {
    /** [PlaybackSettingsStore]: per profile, synced. */
    PLAYBACK,

    /** [AppearanceSettingsStore]: how titles show, per profile, synced. */
    APPEARANCE,

    /** [DeviceSettingsStore]: this device only. */
    DEVICE,

    /** [SubtitleSettingsStore]: the subtitle style, this device only. */
    SUBTITLES,

    /** [DownloadSettingsStore]: downloads on this device; offered where the device has downloads (not Apple TV). */
    DOWNLOADS,

    /** [AddonsStore][com.edde746.tortuga.presentation.addons.AddonsStore]: the profile's addons, synced. */
    ADDONS,

    /** [HomeSettingsStore]: Home's rows, per profile, synced. */
    HOME,

    /** [TrackersSettingsStore]: tracker accounts of this profile on this device; offered when the build has a tracker service. */
    TRACKERS,

    /** [AccountSettingsStore]. */
    ACCOUNT,

    /**
     * [DataSettingsStore]: export and import of the profiles' data as a file; offered where the platform has the
     * system's file dialogs (`AppPlatform.hasFileDialogs`).
     */
    DATA,

    /** [SettingsUiState.about]. */
    ABOUT,
}

/**
 * Version, licence, the Trakt credit and third-party notices.
 *
 * @param license what the GPL-3.0 means for the user, with where the source is.
 * @param traktCredit "Tortuga uses Trakt but is not endorsed or certified by Trakt.", which Trakt's API Use Policy asks
 *   every app using its API to show on its About page.
 * @param notices every third-party component in this build and its licence; `showNotice(index)` shows its page
 *   ([notice]), `openNotice(index)` opens its project's page in the browser.
 * @param notice the component whose page shows in About's place; null while About's list shows.
 */
data class AboutUiState(
    val appName: String,
    val version: String,
    val license: String,
    val sourceLabel: String,
    val licenseLabel: String,
    val traktCredit: String,
    val noticesHeader: String,
    val notices: List<AboutNoticeRow>,
    val notice: NoticePage?,
)

/** A third-party component: "FFmpeg", "GPL-2.0-or-later". */
data class AboutNoticeRow(val name: String, val license: String)

/**
 * A third-party component's page (`showNotice(index)`; `closeNotice` goes back to About's list): its name, licence
 * (SPDX), the label of the link to its project's page (`openNotice(index)` with [index]) and its licence texts, which
 * the app carries in its bundled resources: null while they are read, then one per file. None when the app lacks them
 * (the builds' checks keep that from happening), and then the page has its licence and link only.
 */
data class NoticePage(
    val index: Int,
    val name: String,
    val license: String,
    val linkLabel: String,
    val texts: List<NoticeText>?,
)

/** One licence file of a [NoticePage]: its [path] in the component's folder ("LICENSE") and its text's paragraphs. */
data class NoticeText(val path: String, val paragraphs: List<String>)

/** What the settings screen asks of the app around it; called on the main thread, but for [bundledTexts]. */
interface SettingsHost {
    /** Open the editor of [profileId], the current profile (already unlocked). */
    fun editProfile(profileId: String)

    /** Open the profile picker to switch or manage profiles. */
    fun manageProfiles()

    /** Open [url] in the browser: the source code, the licence, a notice. */
    fun openUrl(url: String)

    /**
     * The files under [folder] in the app's bundled resources (`AppPlatform.bundledTexts`): a notice's licence texts.
     * It reads files, so it is called off the main thread.
     */
    fun bundledTexts(folder: String): List<BundledText>
}
