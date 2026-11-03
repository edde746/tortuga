package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * A pushed screen's (or pane's) heading: a round back button when there is somewhere to go back to ([onBack]), the
 * [title] and an optional [subtitle] line, both from the screen's store, and the screen's [actions] at the end (round
 * icon buttons, as tall as the back button: an addon's "+"). Top-level screens have none: the selected navigation item
 * names them. [contentTitle]: whether [title] is content text (an addon's or a person's), which takes its direction
 * from its first letter ([contentDirection]).
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    contentTitle: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val dimens = LocalTortugaDimens.current
    Row(
        modifier.fillMaxWidth().padding(start = dimens.screenPadding, end = dimens.screenPadding, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            TortugaIconButton(TortugaIcons.ArrowBack, T.nav.back, onBack, Modifier.layoutProbe("header.back"))
        }
        Column(Modifier.weight(1f)) {
            val titleStyle = MaterialTheme.typography.titleLarge
            Text(
                title,
                style = if (contentTitle) titleStyle.contentDirection() else titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions?.invoke(this)
    }
}
