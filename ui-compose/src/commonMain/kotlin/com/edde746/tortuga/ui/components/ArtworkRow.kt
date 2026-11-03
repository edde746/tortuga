package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A list row of [artwork] (a poster, a thumbnail), its [text] in a column and [trailing] (a status mark, a ⋮) at its
 * end, [gap] apart: the text beside the artwork, taking the room between, or, [stacked] (a large font scale,
 * [com.edde746.tortuga.ui.adaptive.largeFontScale]), under it across the row, [gap] below, with [trailing] at the end
 * of the artwork's line. A stacked row's text is meant whole: its callers lift their line limits.
 */
@Composable
internal fun ArtworkRow(
    stacked: Boolean,
    gap: Dp,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    text: @Composable ColumnScope.() -> Unit,
) {
    val lines = Arrangement.spacedBy(2.dp)
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                artwork()
                Spacer(Modifier.weight(1f))
                trailing()
            }
            Column(textModifier.fillMaxWidth(), verticalArrangement = lines, content = text)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            artwork()
            Column(textModifier.weight(1f), verticalArrangement = lines, content = text)
            trailing()
        }
    }
}
