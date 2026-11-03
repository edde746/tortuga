package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlinx.coroutines.delay

/** Content is on its way: Material's (Expressive) loading indicator, with [text] under it when the store has one. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier.fillMaxSize().padding(LocalTortugaDimens.current.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator(Modifier.size(64.dp))
        if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, color = TortugaColors.TextMuted)
    }
}

/**
 * Something failed: [message] (worded by the store) and, with [onRetry], a retry button that takes focus so a remote
 * user can act at once.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = T.nav.retry,
) {
    val retryFocus = remember { FocusRequester() }
    MessageState(icon = TortugaIcons.ErrorOutline, title = title, message = message, modifier = modifier) {
        if (onRetry != null) {
            TortugaButton(retryLabel, onRetry, Modifier.focusRequester(retryFocus), style = ButtonStyle.PRIMARY, icon = TortugaIcons.Refresh)
            LaunchedEffect(Unit) { retryFocus.requestFocus() }
        }
    }
}

/** Nothing to show: [message] (from the store) under [icon]; [action] for what the user can do about it. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = TortugaIcons.Movie,
    action: (@Composable () -> Unit)? = null,
) {
    MessageState(icon = icon, title = null, message = message, modifier = modifier) { action?.invoke() }
}

/**
 * The device is offline ([OfflineState], Home's, Discover's and Search's): its title and text in place of the catalogs,
 * and Go to Downloads ([onOpenDownloads]) where this device has downloads. [actionModifier] goes to the action (a
 * screen's focus memory, so a remote lands on it). Harness names: `offline.state` (the region: bounds only, no focus),
 * `offline.openDownloads`.
 */
@Composable
fun OfflineMessage(state: OfflineState, onOpenDownloads: () -> Unit, modifier: Modifier = Modifier, actionModifier: Modifier = Modifier) {
    MessageState(icon = TortugaIcons.CloudOff, title = state.title, message = state.text, modifier = modifier.layoutProbe("offline.state", reportsFocus = false)) {
        state.actionText?.let {
            TortugaButton(it, onOpenDownloads, actionModifier.layoutProbe("offline.openDownloads"), style = ButtonStyle.PRIMARY, icon = TortugaIcons.Download)
        }
    }
}

/**
 * What this profile may not see, in place of it (a title blocked on a kids profile): a lock, [title], [reason] and the
 * [actionLabel] button ([onAction]: asking a parent), which takes focus so a remote user can act at once. Harness
 * names: [probe] (the region: bounds only, no focus), `[probe].action`.
 */
@Composable
fun BlockedMessage(title: String, reason: String, actionLabel: String, onAction: () -> Unit, probe: String, modifier: Modifier = Modifier) {
    val action = remember { FocusRequester() }
    MessageState(icon = TortugaIcons.Lock, title = title, message = reason, modifier = modifier.layoutProbe(probe, reportsFocus = false)) {
        TortugaButton(actionLabel, onAction, Modifier.focusRequester(action).layoutProbe("$probe.action"), style = ButtonStyle.PRIMARY, icon = TortugaIcons.Lock)
        LaunchedEffect(Unit) { action.requestFocus() }
    }
}

/**
 * A store's one-off message at the bottom of a screen ("Sam joined", "Added to Downloads"; [probe] names it for the
 * harness) for [TOAST_MILLIS], or for the longer time the user's accessibility setting asks for (Android's "Time to
 * take action"), then [onShown] (the store's dismiss): a message the store keeps until told it was shown. A new [text]
 * shows for the whole time again. TalkBack speaks the text as it shows and as it changes, wherever its focus is.
 */
@Composable
fun TimedToast(text: String, probe: String, onShown: () -> Unit, modifier: Modifier = Modifier) {
    val shown by rememberUpdatedState(onShown)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(text) {
        delay(accessibility?.calculateRecommendedTimeoutMillis(TOAST_MILLIS, containsText = true) ?: TOAST_MILLIS)
        shown()
    }
    Surface(modifier.layoutProbe(probe), shape = MaterialTheme.shapes.extraLarge, color = TortugaColors.SurfaceHighest, contentColor = TortugaColors.Text) {
        Text(
            text,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** How long a [TimedToast] shows unless the user's accessibility setting asks for longer. */
const val TOAST_MILLIS = 4_000L

@Composable
private fun MessageState(
    icon: ImageVector,
    title: String?,
    message: String,
    modifier: Modifier,
    actions: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().padding(LocalTortugaDimens.current.screenPadding), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(48.dp), tint = TortugaColors.TextFaint)
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = TortugaColors.TextMuted,
                textAlign = TextAlign.Center,
            )
            actions()
        }
    }
}
