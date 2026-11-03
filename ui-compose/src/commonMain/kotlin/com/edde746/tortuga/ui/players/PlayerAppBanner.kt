package com.edde746.tortuga.ui.players

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.players.PlayerAppBanner
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TopOverlay
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * A hand-off to a player app ([PlayerAppBanner], `AppSessionState.playerApp`): one line at the top of the window over
 * every screen, sheet and dialog ([TopOverlay]): its text with a spinner while the hand-off is under way, "Play in
 * Tortuga" after it failed ([onPlayInTortuga]), and Cancel or Dismiss ([onDismiss]). On narrow windows the actions go
 * under the text. A tap on the line stays on it (the sheet under it doesn't close). On TV it takes focus as soon as it
 * offers "Play in Tortuga" (a remote couldn't reach it past the Streams sheet otherwise), and Back then dismisses it.
 * TalkBack speaks its text as it shows and as it changes ("Opening in VLC…", then "VLC isn't installed on this
 * device."), wherever its focus is.
 *
 * Harness names: `playerApp.banner`, `playerApp.banner.playInTortuga`, `playerApp.banner.dismiss`.
 */
@Composable
internal fun PlayerAppBannerLine(banner: PlayerAppBanner, onPlayInTortuga: () -> Unit, onDismiss: () -> Unit) {
    val layout = LocalWindowLayout.current
    val dimens = LocalTortugaDimens.current
    val playInTortuga = remember { FocusRequester() }
    val takesFocus = layout.formFactor == FormFactor.TV && banner.playInTortugaText != null && !banner.busy
    TopOverlay(focus = playInTortuga.takeIf { takesFocus }, onBack = onDismiss) {
        Box(
            Modifier.fillMaxWidth().padding(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.sectionSpacing),
            contentAlignment = Alignment.TopCenter,
        ) {
            val card = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } }
                .background(TortugaColors.SurfaceHighest, MaterialTheme.shapes.large)
                .layoutProbe("playerApp.banner")
            val actions: @Composable () -> Unit = {
                banner.playInTortugaText?.let {
                    TortugaButton(
                        it,
                        onPlayInTortuga,
                        Modifier.focusRequester(playInTortuga).layoutProbe("playerApp.banner.playInTortuga"),
                        style = ButtonStyle.PRIMARY,
                    )
                }
                TortugaButton(banner.dismissText, onDismiss, Modifier.layoutProbe("playerApp.banner.dismiss"), style = ButtonStyle.TEXT)
            }
            if (layout.widthClass == WidthClass.COMPACT) {
                Column(card.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BannerText(banner)
                    }
                    OverlayActions { actions() }
                }
            } else {
                Row(
                    card.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BannerText(banner)
                    actions()
                }
            }
        }
    }
}

/** The banner's spinner while it is busy (else the error mark once it offers Play in Tortuga) and its text. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.BannerText(banner: PlayerAppBanner) {
    when {
        banner.busy -> LoadingIndicator(Modifier.size(24.dp))
        banner.playInTortugaText != null -> Icon(TortugaIcons.ErrorOutline, contentDescription = null, Modifier.size(24.dp), tint = TortugaColors.Error)
        else -> Icon(TortugaIcons.Info, contentDescription = null, Modifier.size(24.dp))
    }
    Text(banner.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}
