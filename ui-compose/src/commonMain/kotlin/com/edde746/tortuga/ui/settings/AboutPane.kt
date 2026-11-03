package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.settings.AboutUiState
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * About: the app and its version, what the GPL-3.0 means with links to the source and the licence, the Trakt credit
 * under them, and every third-party component with its licence, each row showing its page ([onShowNotice]): the page
 * ([NoticePane]) takes the list's place while [AboutUiState.notice] is set, and the list comes back where it was left,
 * focus on the row that opened the page.
 *
 * Harness names: `settings.about.source`, `settings.about.license`, `settings.about.notice.<i>`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutPane(
    about: AboutUiState,
    onSource: () -> Unit,
    onLicense: () -> Unit,
    onShowNotice: (Int) -> Unit,
    onOpenNotice: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept while a notice's page shows.
    val listState = rememberLazyListState()
    val focus = rememberFocusMemory()
    val notice = about.notice
    if (notice != null) {
        NoticePane(notice, onLink = { onOpenNotice(notice.index) }, modifier)
        return
    }
    SettingsList(modifier, listState) {
        item(key = "app") {
            Column(
                Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(about.appName, style = MaterialTheme.typography.headlineSmall)
                Text(about.version, style = MaterialTheme.typography.titleSmall, color = TortugaColors.TextMuted)
                Text(about.license, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TortugaButton(about.sourceLabel, onSource, Modifier.layoutProbe("settings.about.source"), style = ButtonStyle.PRIMARY, icon = SettingsIcons.Code)
                    TortugaButton(about.licenseLabel, onLicense, Modifier.layoutProbe("settings.about.license"), style = ButtonStyle.SECONDARY, icon = SettingsIcons.Description)
                }
            }
        }
        item(key = "trakt") { SettingsNote(about.traktCredit) }
        settingsGroup(
            "notices",
            about.noticesHeader,
            about.notices.mapIndexed { index, notice ->
                SettingsItem("notice:$index") { shape ->
                    SettingsLinkRow(
                        notice.name,
                        subtitle = notice.license,
                        value = null,
                        onClick = { onShowNotice(index) },
                        shape = shape,
                        modifier = Modifier.rememberFocus(focus, "notice:$index").layoutProbe("settings.about.notice.$index"),
                    )
                }
            },
        )
    }
    // Back from a notice's page: focus on its row (a remote's, a keyboard's; none after a touch).
    RestoreFocus(focus, ready = true, fallback = null)
}
