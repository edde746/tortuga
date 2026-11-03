package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.NoticePage
import com.edde746.tortuga.presentation.settings.NoticeText
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.focus.FocusInto
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.scrollPastControls
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * A third-party component's page in About's place ([NoticePage]; the section's heading names the component and its
 * back closes the page): its licence and a button to its project's page ([onLink]) in a card as About's own, then each
 * of its licence files, the file's path in a group heading's style over its paragraphs, nothing while they are read.
 * One lazy column in the panes' content column, a paragraph per item, since some components' texts run to megabytes
 * (Google Play services' AARs' third-party licences); on a remote, Down from the button scrolls the text with focus
 * staying on the button ([scrollPastControls]). The text can be selected, but on TV, where the selection would be a
 * focus stop of its own.
 *
 * Harness names: `settings.notice.link`, `settings.notice.text` (the first licence file's heading).
 */
@Composable
internal fun NoticePane(page: NoticePage, onLink: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalTortugaDimens.current
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val list = rememberLazyListState()
    val link = remember { FocusRequester() }
    val content = @Composable {
        LazyColumn(
            modifier.contentColumn().fillMaxSize().scrollPastControls(list),
            state = list,
            contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.focusReach, bottom = dimens.sectionSpacing),
        ) {
            item(key = "card") {
                Column(
                    Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(page.license, style = MaterialTheme.typography.titleMedium)
                    TortugaButton(
                        page.linkLabel, onLink, Modifier.focusRequester(link).layoutProbe("settings.notice.link"),
                        style = ButtonStyle.SECONDARY, icon = TortugaIcons.OpenInNew,
                    )
                }
            }
            page.texts?.forEachIndexed { index, text -> noticeText(index, text) }
        }
    }
    if (tv) content() else SelectionContainer { content() }
    // The row that opened the page has left with the list.
    FocusInto(link, key = page.index)
}

/** A licence file: its path, 8 dp below what is above it, then its paragraphs, 12 dp apart. */
private fun LazyListScope.noticeText(index: Int, text: NoticeText) {
    item(key = "path:$index") {
        val probe = if (index == 0) Modifier.layoutProbe("settings.notice.text", reportsFocus = false) else Modifier
        GroupHeader(text.path, probe.padding(top = 8.dp))
    }
    itemsIndexed(text.paragraphs, key = { paragraph, _ -> "text:$index:$paragraph" }) { paragraph, words ->
        Text(
            words,
            Modifier.padding(start = 16.dp, end = 16.dp, top = if (paragraph == 0) 0.dp else 12.dp),
            style = MaterialTheme.typography.bodyMedium.contentDirection(),
        )
    }
}
