package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.SettingsSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.presentation.settings.SettingsProfileCard
import com.edde746.tortuga.presentation.settings.SettingsSection
import com.edde746.tortuga.presentation.settings.SettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.addons.AddonsActions
import com.edde746.tortuga.ui.addons.AddonsSettingsRoute
import com.edde746.tortuga.ui.components.Chevron
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.ScreenHeader
import com.edde746.tortuga.ui.components.SystemBackHandler
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.FocusInto
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.focusHomeGroup
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.ScrimWhileScrolled
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.profiles.ProfileAvatarTile
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * The Settings screen of [app] over its [SettingsSession] (shared: kept for the session, Settings being a tab, so a
 * section left open, and its store, are still there when the user comes back from a screen it opened, the browser or
 * another tab).
 */
@Composable
fun SettingsRoute(app: AppSession) {
    // Once per appearance: the entry still draws while it animates out after its route left the stack, and an entry
    // first composed after that gets no session and draws nothing.
    val session = remember(app) { app.settingsSession() } ?: return
    val state by session.main.state.collectAsState()
    SettingsScreen(
        state = state,
        canManageProfiles = app.canSwitchProfile,
        onOpen = session.main::open,
        onClose = session.main::closeSection,
        onCloseNotice = session.main::closeNotice,
        onEditProfile = session.main::editProfile,
        onManageProfiles = session.main::manageProfiles,
        sectionActions = {
            when (it) {
                SettingsSection.ADDONS -> session.addons?.let { store -> AddonsActions(store) }
                SettingsSection.HOME -> session.home?.let { store -> HomeActions(store) }
                else -> {}
            }
        },
        sectionContent = { SettingsSectionContent(session, it) },
    )
}

/**
 * Settings: the current profile, then the sections as one group. On wide windows (desktop, tablets in
 * landscape) the list stays at the start and the open section fills the rest, its heading and rows in a column centred
 * there ([contentColumn]); on phones, medium windows and TV the list is a page of its own and a section is pushed over
 * it (Back returns to the list). Every text comes from [state] and the sections' own states; [sectionContent] draws a
 * section, [sectionActions] its actions at the end of its heading (Addons' type filter and "+"). While a third-party
 * component's page shows in About ([AboutUiState.notice][com.edde746.tortuga.presentation.settings.AboutUiState.notice])
 * the heading names the component, and its back button and the system's back close the page ([onCloseNotice]).
 *
 * Harness names: `settings.profile.edit`, `settings.profile.manage`, `settings.section.<section>`, `settings.pane` (the
 * open section's place beside the list on wide windows) and `settings.header` (its heading).
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    canManageProfiles: Boolean,
    onOpen: (SettingsSection) -> Unit,
    onClose: () -> Unit,
    onCloseNotice: () -> Unit,
    onEditProfile: () -> Unit,
    onManageProfiles: () -> Unit,
    sectionActions: @Composable (SettingsSection) -> Unit,
    sectionContent: @Composable (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    // Outside the pages: back from a section pushed over the list finds the list scrolled where it was left, not at
    // its top.
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = !tv && maxWidth >= 840.dp
        // About a third of the width, so a section's summary takes two lines at most where there is room.
        val listWidth = (maxWidth * 0.32f).coerceIn(340.dp, 400.dp)
        val shown = state.section ?: if (wide) state.sections.firstOrNull()?.section else null
        // A notice's page in About.
        val notice = shown == SettingsSection.ABOUT && state.about.notice != null
        // The open section: focus that drops in it (a row disabled or gone as its action runs) goes to its first row.
        val content = remember { FocusRequester() }
        val pane = Modifier.focusHomeGroup { content.requestFocus() }.focusRequester(content).focusGroup()
        when {
            wide -> Row(Modifier.fillMaxSize()) {
                SectionList(
                    state, selected = shown, canManageProfiles, onOpen, onEditProfile, onManageProfiles, listState,
                    Modifier.width(listWidth).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight().layoutProbe("settings.pane", reportsFocus = false)) {
                    if (shown != null) {
                        if (notice) SystemBackHandler(onBack = onCloseNotice)
                        ScreenHeader(
                            sectionTitle(state, shown), Modifier.contentColumn().layoutProbe("settings.header", reportsFocus = false),
                            onBack = onCloseNotice.takeIf { notice }, contentTitle = notice,
                        ) { sectionActions(shown) }
                        Box(Modifier.weight(1f).then(pane)) { sectionContent(shown) }
                    }
                }
            }
            shown == null -> SectionList(state, selected = null, canManageProfiles, onOpen, onEditProfile, onManageProfiles, listState, Modifier.fillMaxSize())
            else -> {
                SystemBackHandler(onBack = onClose)
                // Composed after the section's, so the system's back closes the page first.
                if (notice) SystemBackHandler(onBack = onCloseNotice)
                Column(Modifier.fillMaxSize().padding(top = statusBarOverlap)) {
                    ScreenHeader(
                        sectionTitle(state, shown), Modifier.contentColumn().layoutProbe("settings.header", reportsFocus = false),
                        onBack = if (notice) onCloseNotice else onClose, contentTitle = notice,
                    ) { sectionActions(shown) }
                    Box(Modifier.weight(1f).then(pane)) { sectionContent(shown) }
                }
                // The row that opened the section has left with the list: focus goes to the section's first row,
                // not back to the navigation.
                FocusInto(content, key = shown)
            }
        }
    }
}

/** The section's heading: About's names the notice whose page shows. */
private fun sectionTitle(state: SettingsUiState, section: SettingsSection): String =
    if (section == SettingsSection.ABOUT) state.about.notice?.name ?: state.sectionTitle(section) else state.sectionTitle(section)

private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.PLAYBACK -> TortugaIcons.PlayArrow
        SettingsSection.APPEARANCE -> SettingsIcons.Palette
        SettingsSection.DEVICE -> SettingsIcons.Tv
        SettingsSection.SUBTITLES -> TortugaIcons.Subtitles
        SettingsSection.DOWNLOADS -> TortugaIcons.Download
        SettingsSection.ADDONS -> TortugaIcons.Extension
        SettingsSection.HOME -> TortugaIcons.Home
        SettingsSection.TRACKERS -> TortugaIcons.Sync
        SettingsSection.ACCOUNT -> SettingsIcons.AccountCircle
        SettingsSection.DATA -> SettingsIcons.ImportExport
        SettingsSection.ABOUT -> TortugaIcons.Info
    }

/**
 * The first page: the current profile, then the sections as one connected group. No title of its own: the selected
 * navigation item already names the screen.
 */
@Composable
private fun SectionList(
    state: SettingsUiState,
    selected: SettingsSection?,
    canManageProfiles: Boolean,
    onOpen: (SettingsSection) -> Unit,
    onEditProfile: () -> Unit,
    onManageProfiles: () -> Unit,
    listState: LazyListState,
    modifier: Modifier,
) {
    val dimens = LocalTortugaDimens.current
    val focus = rememberFocusMemory()
    ScrimWhileScrolled { listState.canScrollBackward }
    LazyColumn(
        modifier.contentColumn().fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = 16.dp + statusBarOverlap, bottom = dimens.sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        // One item from the first frame, at its final size: a lazy list keeps its first visible item in place, so a
        // card inserted above the first section later would sit above the window, and one that grew there would move
        // the sections from under a click.
        item(key = "profile") {
            ProfileCard(state.profile, canManageProfiles, onEditProfile, onManageProfiles, Modifier.padding(bottom = 16.dp))
        }
        itemsIndexed(state.sections, key = { _, row -> row.section.name }) { index, row ->
            val name = row.section.probeName
            GroupRow(
                title = row.title,
                onClick = { onOpen(row.section) },
                modifier = Modifier.rememberFocus(focus, name).layoutProbe("settings.section.$name"),
                shape = groupShape(index, state.sections.size),
                subtitle = row.summary,
                icon = row.section.icon,
                selected = row.section == selected,
                trailing = { Chevron() },
            )
        }
    }
    RestoreFocus(focus, ready = state.sections.isNotEmpty(), fallback = (selected ?: state.sections.firstOrNull()?.section)?.name?.lowercase())
}

/**
 * The current profile ("Profiles"): its avatar (as the profile picker draws it), name and lock, then two icon buttons
 * named by their labels: a pen that edits it (none where the profile can't be edited from here: a kids profile's
 * [SettingsProfileCard.editLabel] is null) and two arrows that switch or manage profiles. While it is read ([profile]
 * null), the card empty at the size it will have: its content laid out unseen and out of reach.
 */
@Composable
private fun ProfileCard(profile: SettingsProfileCard?, canManage: Boolean, onEdit: () -> Unit, onManage: () -> Unit, modifier: Modifier) {
    val text = T.settings.profile
    val content = if (profile != null) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}
    // Only once the profile is read: the placeholder's buttons are no target.
    fun Modifier.probe(name: String) = if (profile != null) layoutProbe(name) else this
    Row(
        modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large).then(content).padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (profile != null) ProfileAvatarTile(profile.initial, profile.avatar, AvatarSize) else Spacer(Modifier.size(AvatarSize))
        Column(Modifier.weight(1f)) {
            Text(profile?.name.orEmpty(), style = MaterialTheme.typography.titleMedium.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // One line, as the placeholder's: the card's height doesn't depend on the lock.
            Text(profile?.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (profile == null || profile.editLabel != null) {
                TortugaIconButton(TortugaIcons.Edit, profile?.editLabel ?: text.edit, onEdit, Modifier.probe("settings.profile.edit"), enabled = profile != null)
            }
            if (canManage) {
                TortugaIconButton(SettingsIcons.SwapHoriz, profile?.manageLabel ?: text.manage, onManage, Modifier.probe("settings.profile.manage"), enabled = profile != null)
            }
        }
    }
}

/** The profile card's avatar. */
private val AvatarSize = 48.dp

/** A section of [session] over its store. */
@Composable
private fun SettingsSectionContent(session: SettingsSession, section: SettingsSection) {
    when (section) {
        SettingsSection.PLAYBACK -> session.playback?.let { PlaybackSettingsRoute(it, session.player) }
        SettingsSection.APPEARANCE -> session.appearance?.let { AppearanceSettingsRoute(it) }
        SettingsSection.DEVICE -> session.device?.let { DeviceSettingsRoute(it) }
        SettingsSection.SUBTITLES -> session.subtitles?.let { SubtitleSettingsRoute(it) }
        SettingsSection.ACCOUNT -> session.account?.let { AccountSettingsRoute(it) }
        SettingsSection.TRACKERS -> session.trackers?.let { TrackersSettingsRoute(it) }
        SettingsSection.HOME -> session.home?.let { HomeSettingsRoute(it) }
        SettingsSection.DOWNLOADS -> session.downloads?.let { DownloadSettingsRoute(it) }
        SettingsSection.DATA -> session.data?.let { DataSettingsRoute(it) }
        SettingsSection.ABOUT -> {
            val state by session.main.state.collectAsState()
            AboutPane(state.about, session.main::openSourceCode, session.main::openLicense, session.main::showNotice, session.main::openNotice)
        }
        SettingsSection.ADDONS -> session.addons?.let { AddonsSettingsRoute(it) }
    }
}
